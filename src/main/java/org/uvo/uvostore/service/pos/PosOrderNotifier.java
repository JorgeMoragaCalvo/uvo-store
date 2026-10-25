package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.service.metrics.BusinessMetrics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lo que el e-commerce sabe de cómo registrar una venta en UvoPOS, partido en las dos mitades del outbox
 * (PROD-13b):
 *
 * <ul>
 *   <li><b>Al confirmar el pago</b>, dentro de su transacción: a qué empresas hay que enviarla
 *       ({@link #companiesToNotify}). Una fila del outbox por empresa.</li>
 *   <li><b>Al despachar</b>, cada fila por su cuenta: qué enviar ({@link #prepare}) y, si el POS respondió,
 *       comparar el stock que dice tener con el nuestro ({@link #checkStockDivergence}). La llamada de red la
 *       hace el despachador entre las dos, fuera de toda transacción.</li>
 * </ul>
 *
 * <p>Antes (G2) esto notificaba a todas las empresas de una vez y contaba el resultado en
 * {@code orders.sync_attempts}: si fallaba una, el reintento volvía a enviar a las que ya habían respondido.
 * Con una fila por empresa, cada una se entrega, se reintenta o falla por separado.
 */
@Service
public class PosOrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(PosOrderNotifier.class);

    private final OrderRepository orderRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosOrderPayloadMapper payloadMapper;
    private final PosIntegrationPolicy posPolicy;
    private final int divergenceAlertThreshold;
    private final BusinessMetrics metrics;

    public PosOrderNotifier(OrderRepository orderRepository, ProductSyncMappingRepository mappingRepository,
                            ProductVariationSyncMappingRepository variantMappingRepository,
                            PosConnectionRepository posConnectionRepository, PosOrderPayloadMapper payloadMapper,
                            PosIntegrationPolicy posPolicy,
                            @Value("${app.pos.stock-divergence-alert-threshold:3}") int divergenceAlertThreshold,
                            BusinessMetrics metrics) {
        this.metrics = metrics;
        this.orderRepository = orderRepository;
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.payloadMapper = payloadMapper;
        this.posPolicy = posPolicy;
        this.divergenceAlertThreshold = divergenceAlertThreshold;
    }

    /**
     * Las empresas del POS que tienen productos en esta orden, ordenadas. Corre dentro de la transacción
     * que confirma el pago, así que <b>no puede lanzar por datos raros</b>: una excepción aquí desharía la
     * confirmación entera (el pago cobrado quedaría PENDING). Por eso solo usa consultas que devuelven
     * listas o existencias, nunca un {@code Optional} sobre una columna que no es única.
     *
     * <p>PROD-12: con la integración apagada —de plataforma o de tienda— devuelve vacío, así que no se
     * encola nada y no se emite ningún documento.
     */
    public List<Long> companiesToNotify(Order order) {
        if (!posPolicy.isEnabledFor(order.getStore())) {
            log.debug("Integración POS deshabilitada — no se encola la venta order_id={}", order.getId());
            return List.of();
        }

        Set<Long> productIds = order.getItems().stream()
                .map(item -> item.getProduct().getId())
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return List.of();
        }

        List<ProductSyncMapping> mappings = mappingRepository.findByProductIdIn(productIds);
        Set<Long> mappedProducts = mappings.stream()
                .map(mapping -> mapping.getProduct().getId())
                .collect(Collectors.toSet());
        // POS-5. Una línea de variación solo se puede registrar si su variante está mapeada: si no, prepare() no la
        // envía (el POS descontaría otra cosa), y aquí cuenta como no cubierta.
        Set<Long> mappedVariations = mappings.isEmpty() ? Set.of() : variantMappingRepository
                .findByMappingIdIn(mappings.stream().map(ProductSyncMapping::getId).toList()).stream()
                .map(variantMapping -> variantMapping.getVariation().getId())
                .collect(Collectors.toSet());
        long unmappedItems = order.getItems().stream()
                .filter(item -> !mappedProducts.contains(item.getProduct().getId())
                        || (item.getVariation() != null && !mappedVariations.contains(item.getVariation().getId())))
                .count();
        if (unmappedItems > 0) {
            reportIncompleteCoverage(order, unmappedItems);
        }

        return mappings.stream()
                .map(ProductSyncMapping::getCompanyId)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Lo que hay que enviar a una empresa, armado en una transacción de lectura corta: la llamada de red
     * va después y fuera de ella, para no tener una conexión de Hikari tomada mientras el POS responde.
     *
     * <p>Vuelve a comprobar todo lo que pudo cambiar desde el pago. Entre el pago y el envío ahora pueden
     * pasar minutos u horas (un POS caído), y en ese tiempo la orden se puede cancelar o reembolsar, o el
     * POS apagarse: ninguno de esos casos debe terminar en un documento tributario.
     */
    @Transactional(readOnly = true)
    public PosSaleRequest prepare(Long orderId, Long companyId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return PosSaleRequest.skip("La orden ya no existe");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID
                || order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            return PosSaleRequest.skip("La orden ya no está pagada (pago " + order.getPaymentStatus()
                    + ", estado " + order.getStatus() + "): no se registra una venta deshecha");
        }
        if (!posPolicy.isEnabledFor(order.getStore())) {
            return PosSaleRequest.skip("Integración POS deshabilitada");
        }

        PosConnection connection = posConnectionRepository.findByCompanyId(companyId)
                .filter(PosConnection::isActive)
                .orElse(null);
        if (connection == null) {
            // Nada salió hacia el POS, así que reintentar es seguro: quizá la conexión se reactive.
            return PosSaleRequest.notSent("Conexión POS no encontrada o inactiva (company_id=" + companyId + ")");
        }

        List<PosOrderItem> items = order.getItems().stream()
                .map(item -> mappingRepository.findByProductIdAndCompanyId(item.getProduct().getId(), companyId)
                        .flatMap(mapping -> toPosItem(item, mapping)))
                .flatMap(Optional::stream)
                .toList();
        if (items.isEmpty()) {
            return PosSaleRequest.skip("La orden ya no tiene productos de la empresa " + companyId);
        }

        return PosSaleRequest.ready(connection, payloadMapper.toPayload(order, companyId, items));
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
     *
     * <p>PROD-13b: corre después de dar la venta por entregada, en su propia transacción de lectura. Un
     * fallo aquí no puede convertir en dudosa una venta que el POS ya confirmó.
     */
    @Transactional(readOnly = true)
    public void checkStockDivergence(Map<String, Object> response, Long companyId) {
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
            Long variantId = PosCatalogSnapshot.asLong(result.get("variant_id"));

            mappingRepository.findByExternalIdAndCompanyId(externalId, companyId).ifPresent(mapping -> {
                // POS-5. Un resultado de variante se compara con esa variación. El padre de un producto variable
                // es la suma de sus variantes: compararlo con el stock de una talla avisaría en cada venta.
                int ourStock;
                String what;
                if (variantId != null) {
                    var variant = variantMappingRepository.findByMappingIdAndExternalVariantId(mapping.getId(), variantId);
                    if (variant.isEmpty()) {
                        return;
                    }
                    ourStock = variant.get().getVariation().getStock();
                    what = "la variación %d del producto %d".formatted(variant.get().getVariation().getId(),
                            mapping.getProduct().getId());
                } else if (mapping.getProduct().getProductType() == ProductType.VARIABLE) {
                    return;
                } else {
                    ourStock = mapping.getProduct().getStock();
                    what = "producto %d".formatted(mapping.getProduct().getId());
                }
                int gap = Math.abs(ourStock - posStock);
                if (gap == 0) {
                    return;
                }
                log.warn("Divergencia de stock con el POS: {} tiene {} en la tienda y {} según el POS (company_id={})",
                        what, ourStock, posStock, companyId);
                // Una unidad de diferencia es lo normal mientras las dos puntas venden a la vez;
                // una diferencia grande significa que una de las dos está mal, y eso sí hay que
                // mirarlo. El umbral separa lo uno de lo otro sin inundar Sentry.
                if (gap > divergenceAlertThreshold) {
                    metrics.stockDivergences(BusinessMetrics.DivergenceSource.ORDER_RESPONSE, 1);
                    Sentry.captureMessage("Divergencia de stock con el POS: %s tiene %d en la tienda y %d según el POS"
                            .formatted(what, ourStock, posStock));
                }
            });
        }
    }

    /**
     * POS-5. Una línea de variación lleva su variante del POS. Si la variación no está mapeada, la línea no se envía:
     * mandarla sin variante haría que el POS descontara otra cosa. Ya se avisó al confirmar el pago
     * ({@link #reportIncompleteCoverage}).
     */
    private Optional<PosOrderItem> toPosItem(OrderItem item, ProductSyncMapping mapping) {
        if (item.getVariation() == null) {
            return Optional.of(new PosOrderItem(mapping.getExternalId(), null, item.getQuantity(), item.getPrice(),
                    item.getSubtotal(), item.getTaxAmount()));
        }
        return variantMappingRepository.findByMappingIdAndVariationId(mapping.getId(), item.getVariation().getId())
                .map(variant -> new PosOrderItem(mapping.getExternalId(), variant.getExternalVariantId(),
                        item.getQuantity(), item.getPrice(), item.getSubtotal(), item.getTaxAmount()));
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
     *
     * <p>PROD-13b: el aviso sale después del commit. Esto corre dentro de la transacción del pago, y si
     * algo posterior la deshace, la alerta sería de una venta que no ocurrió.
     */
    private void reportIncompleteCoverage(Order order, long unmappedItems) {
        if (!posConnectionRepository.existsActiveForStore(order.getStore().getId())) {
            return;
        }

        String message = "Orden notificada al POS de forma incompleta: %d de %d ítems sin ProductSyncMapping (order_number=%s)"
                .formatted(unmappedItems, order.getItems().size(), order.getOrderNumber());
        afterCommit(() -> {
            log.warn(message);
            Sentry.captureMessage(message);
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
