package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La notificación de una orden a UvoPOS, extraída de {@code PosNotificationListener} en G2: ahora
 * tiene dos llamadores —el listener {@code AFTER_COMMIT} y el reintento programado
 * ({@link PosNotificationRetryJob})— y duplicar esta lógica en el job sería la forma más rápida de
 * que las dos versiones se separaran.
 *
 * <p>G2 añade además la contabilidad que faltaba. {@code Order.posSynced} y {@code Order.syncAttempts}
 * existían desde la migración y los inicializaba el checkout, pero <b>ningún código los leía ni los
 * actualizaba</b>: una notificación fallida no dejaba más rastro que una línea de log, así que no
 * había forma de saber qué órdenes no llegaron al POS ni de reintentarlas.
 */
@Service
public class PosOrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(PosOrderNotifier.class);

    private final OrderRepository orderRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosClient posClient;
    private final PosOrderPayloadMapper payloadMapper;
    private final int divergenceAlertThreshold;

    public PosOrderNotifier(OrderRepository orderRepository, ProductSyncMappingRepository mappingRepository,
                            PosConnectionRepository posConnectionRepository, PosClient posClient,
                            PosOrderPayloadMapper payloadMapper,
                            @Value("${app.pos.stock-divergence-alert-threshold:3}") int divergenceAlertThreshold) {
        this.orderRepository = orderRepository;
        this.mappingRepository = mappingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.posClient = posClient;
        this.payloadMapper = payloadMapper;
        this.divergenceAlertThreshold = divergenceAlertThreshold;
    }

    /**
     * Notifica la orden a todas las empresas que tengan productos suyos sincronizados, y deja
     * escrito el resultado en la propia orden. REQUIRES_NEW porque el llamador puede ser un listener
     * AFTER_COMMIT —donde ya no hay transacción viva— y porque cada orden del job de reintento debe
     * ser independiente de las demás.
     *
     * @return true si no quedó nada pendiente: o se notificó a todas las empresas, o no había nada
     *         que notificar.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notifyOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        List<ProductSyncMapping> mappings = order.getItems().stream()
                .map(item -> mappingRepository.findByProductId(item.getProduct().getId()))
                .flatMap(Optional::stream)
                .toList();

        reportIncompleteCoverage(order, mappings.size());

        Set<Long> companyIds = mappings.stream()
                .map(ProductSyncMapping::getCompanyId)
                .collect(Collectors.toSet());

        if (companyIds.isEmpty()) {
            // syncAttempts se queda en 0 a propósito: no se intentó nada. Es lo que mantiene estas
            // órdenes fuera del job de reintento, que solo mira las que sí llegaron a intentarlo.
            log.info("Orden sin productos sincronizados, no se notifica a POS order_id={}", order.getId());
            return true;
        }

        order.setSyncAttempts(order.getSyncAttempts() + 1);

        boolean allNotified = true;
        String lastError = null;
        for (Long companyId : companyIds) {
            String error = notifyOneCompany(order, companyId);
            if (error != null) {
                allNotified = false;
                lastError = error;
            }
        }

        // Una orden con dos empresas solo se da por sincronizada cuando las dos respondieron: si se
        // marcara con la primera, la segunda empresa se quedaría sin su documento y sin reintento.
        order.setPosSynced(allNotified);
        order.setLastSyncError(lastError);
        orderRepository.save(order);

        return allNotified;
    }

    /** @return null si se notificó bien, o el mensaje de error si no. */
    private String notifyOneCompany(Order order, Long companyId) {
        try {
            PosConnection connection = posConnectionRepository.findByCompanyId(companyId)
                    .filter(PosConnection::isActive)
                    .orElse(null);
            if (connection == null) {
                log.warn("Conexión POS no encontrada, orden no notificada order_id={} company_id={}", order.getId(), companyId);
                return "Conexión POS no encontrada o inactiva (company_id=" + companyId + ")";
            }

            List<PosOrderItem> items = order.getItems().stream()
                    .map(item -> mappingRepository.findByProductIdAndCompanyId(item.getProduct().getId(), companyId)
                            .map(mapping -> new PosOrderItem(mapping.getExternalId(), item.getQuantity(),
                                    item.getPrice(), item.getSubtotal(), item.getTaxAmount())))
                    .flatMap(Optional::stream)
                    .toList();

            if (items.isEmpty()) {
                log.info("No hay productos sincronizados en esta orden para company_id={} order_id={}", companyId, order.getId());
                return null;
            }

            Map<String, Object> response = posClient.notifyOrder(connection, payloadMapper.toPayload(order, companyId, items));
            log.info("Orden notificada a POS exitosamente order_id={} order_number={} company_id={} response={}",
                    order.getId(), order.getOrderNumber(), companyId, response);

            checkStockDivergence(response, companyId);
            return null;
        } catch (Exception e) {
            // Se sigue tragando la excepción: el llamador puede ser un listener AFTER_COMMIT, donde
            // relanzar no consigue nada porque la transacción ya se confirmó. Lo que cambia en G2 es
            // que ya no muere aquí — va a Sentry y queda escrita en la orden, que es lo que permite
            // reintentarla. Antes, una plataforma SII caída era una línea de log que nadie miraba.
            log.error("Error notificando orden a POS order_id={} company_id={} error={}", order.getId(), companyId, e.getMessage());
            Sentry.captureException(e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /**
     * G3.2, la mitad que sí se puede arreglar sin decidir nada de negocio. Si una orden tiene ítems
     * sin {@code ProductSyncMapping} se notifica solo una parte —y si no tiene ninguno, no se
     * notifica nada—, con lo que el documento tributario sale incompleto o no sale. Qué hacer con
     * eso (¿rechazar la venta? ¿emitir parcial?) es decisión de negocio y sigue abierta; el
     * comportamiento no cambia. Lo que cambia es que deja rastro: hoy no se enteraba nadie.
     *
     * <p>Solo alerta si la tienda tiene una conexión POS activa. Sin ese filtro, cada orden de cada
     * tienda que no usa POS —que es la mayoría— generaría un evento, y la alerta se volvería el
     * ruido que nadie mira, que es exactamente el problema que intenta resolver.
     */
    private void reportIncompleteCoverage(Order order, int mappedItems) {
        int totalItems = order.getItems().size();
        if (mappedItems >= totalItems) {
            return;
        }
        boolean storeUsesPos = posConnectionRepository.findByStoreId(order.getStore().getId())
                .filter(PosConnection::isActive)
                .isPresent();
        if (!storeUsesPos) {
            return;
        }

        String message = "Orden notificada al POS de forma incompleta: %d de %d ítems sin ProductSyncMapping (order_number=%s)"
                .formatted(totalItems - mappedItems, totalItems, order.getOrderNumber());
        log.warn(message);
        Sentry.captureMessage(message);
    }

    /**
     * <b>Cambio de semántica de sincronización (el "adicional" de G3).</b> Esto sobrescribía
     * {@code product.stock} con el valor que devuelve el POS, mientras {@code StockDecrementListener}
     * lo decrementaba por el mismo evento y sin orden garantizado entre los dos: el stock final
     * dependía de quién llegara último.
     *
     * <p>Ya no se sobrescribe. El descuento propio es la autoridad sobre el stock web y lo que
     * responde el POS es una observación: si no coincide, se registra la divergencia en vez de
     * pisarla. Queda marcado como cambio de semántica para que se pueda revisar — la alternativa
     * (que mande el POS) es defendible, pero entonces hay que quitar el descuento propio, no
     * dejarlos compitiendo.
     */
    private void checkStockDivergence(Map<String, Object> response, Long companyId) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return;
        }
        if (!(dataMap.get("results") instanceof List<?> resultList)) {
            return;
        }

        for (Object entry : resultList) {
            if (!(entry instanceof Map<?, ?> result) || !Boolean.TRUE.equals(result.get("success"))) {
                continue;
            }
            Object productId = result.get("product_id");
            Object newStockWeb = result.get("new_stock_web");
            if (productId == null || newStockWeb == null) {
                continue;
            }

            Long externalId = Long.valueOf(productId.toString());
            int posStock = (int) Double.parseDouble(newStockWeb.toString());

            mappingRepository.findByExternalIdAndCompanyId(externalId, companyId).ifPresent(mapping -> {
                int ourStock = mapping.getProduct().getStock();
                int gap = Math.abs(ourStock - posStock);
                if (gap == 0) {
                    return;
                }
                log.warn("Divergencia de stock con el POS: producto {} tiene {} en la tienda y {} según el POS (company_id={})",
                        mapping.getProduct().getId(), ourStock, posStock, companyId);
                // Una unidad de diferencia es lo normal mientras las dos puntas venden a la vez;
                // una diferencia grande significa que una de las dos está mal, y eso sí hay que
                // mirarlo. El umbral separa lo uno de lo otro sin inundar Sentry.
                if (gap > divergenceAlertThreshold) {
                    Sentry.captureMessage("Divergencia de stock con el POS: producto %d tiene %d en la tienda y %d según el POS"
                            .formatted(mapping.getProduct().getId(), ourStock, posStock));
                }
            });
        }
    }
}
