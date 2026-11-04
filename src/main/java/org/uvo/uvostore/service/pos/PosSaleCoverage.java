package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.service.order.CartLineCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GAP-06. A qué empresa del POS va cada línea de una venta, y si la venta se puede registrar entera.
 *
 * <p>Una orden de dos empresas generaba dos documentos y cada uno llevaba los totales de la orden completa
 * ({@link PosOrderPayloadMapper}); una línea sin mapeo se cobraba y no llegaba a ningún documento. Con el POS
 * activo, el carrito y el checkout ahora rechazan las dos cosas: <b>una empresa por orden y nada sin mapeo</b>.
 * Así cada documento coincide con la orden que se cobró.
 *
 * <p>La regla de qué empresa tiene una línea vive solo aquí, y la usan el carrito, el checkout y
 * {@link PosOrderNotifier#companiesToNotify}: lo que se deja comprar es exactamente lo que después se envía.
 * Todos los mapeos cuentan, sin mirar su estado, porque el notificador tampoco lo mira. Una línea de variación
 * necesita además su variante mapeada bajo esa empresa: sin ella, {@code prepare} no la envía.
 *
 * <p>Solo usa consultas que devuelven listas: también corre dentro de la transacción del pago, donde una
 * excepción por un dato raro desharía la confirmación.
 */
@Component
public class PosSaleCoverage {

    private static final Logger log = LoggerFactory.getLogger(PosSaleCoverage.class);

    static final String NOT_FOR_SALE = "Este producto no está disponible para la venta en línea";
    static final String SEPARATE_ORDER = "Este producto se compra en un pedido aparte";

    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosIntegrationPolicy posPolicy;

    public PosSaleCoverage(ProductSyncMappingRepository mappingRepository,
                           ProductVariationSyncMappingRepository variantMappingRepository,
                           ProductRepository productRepository, ProductVariationRepository variationRepository,
                           PosConnectionRepository posConnectionRepository, PosIntegrationPolicy posPolicy) {
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.posPolicy = posPolicy;
    }

    /** Una línea de la venta: el producto y, si es de variación, la variación. */
    public record SaleLine(Long productId, Long variationId) {
    }

    /**
     * Las empresas de cada línea, en el mismo orden. Un conjunto vacío es una línea que no llega a ningún
     * documento; uno de dos o más, un producto mapeado a varias empresas, que entraría en todos.
     */
    public List<Set<Long>> companiesByLine(List<SaleLine> lines) {
        Set<Long> productIds = lines.stream().map(SaleLine::productId).collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return lines.stream().map(line -> Set.<Long>of()).toList();
        }

        List<ProductSyncMapping> mappings = mappingRepository.findByProductIdIn(productIds);
        Map<Long, Set<Long>> companiesByProduct = new HashMap<>();
        for (ProductSyncMapping mapping : mappings) {
            companiesByProduct.computeIfAbsent(mapping.getProduct().getId(), id -> new HashSet<>())
                    .add(mapping.getCompanyId());
        }

        // Las variantes solo se consultan si hay alguna línea de variación: el caso común no paga la consulta.
        Map<Long, Set<Long>> companiesByVariation = new HashMap<>();
        if (!mappings.isEmpty() && lines.stream().anyMatch(line -> line.variationId() != null)) {
            List<ProductVariationSyncMapping> variants = variantMappingRepository.findByMappingIdIn(
                    mappings.stream().map(ProductSyncMapping::getId).toList());
            for (ProductVariationSyncMapping variant : variants) {
                companiesByVariation.computeIfAbsent(variant.getVariation().getId(), id -> new HashSet<>())
                        .add(variant.getMapping().getCompanyId());
            }
        }

        return lines.stream()
                .map(line -> Set.copyOf(line.variationId() != null
                        ? companiesByVariation.getOrDefault(line.variationId(), Set.of())
                        : companiesByProduct.getOrDefault(line.productId(), Set.of())))
                .toList();
    }

    /**
     * Los problemas de cada línea del carrito, con la clave {@code items.N} que el carrito ya pinta. Vacío si el
     * POS no está activo en la tienda o si todo va a una sola empresa.
     *
     * <p>La empresa del pedido es la de la primera línea que tiene exactamente una: las de otra empresa se
     * marcan para comprarse aparte. Una línea que no es de esta tienda se salta: la validación de stock ya la
     * rechaza como no encontrada.
     */
    public Map<String, String> lineErrors(Store store, List<CartLineCommand> lines) {
        if (!isActiveFor(store) || lines.isEmpty()) {
            return Map.of();
        }

        List<SaleLine> saleLines = toSaleLines(store, lines);
        List<SaleLine> resolved = saleLines.stream().filter(Objects::nonNull).toList();
        List<Set<Long>> resolvedCompanies = companiesByLine(resolved);

        Map<String, String> errors = new LinkedHashMap<>();
        Long orderCompany = null;
        int next = 0;
        for (int index = 0; index < saleLines.size(); index++) {
            if (saleLines.get(index) == null) {
                continue;
            }
            Set<Long> companies = resolvedCompanies.get(next++);
            String key = "items." + index;
            if (companies.isEmpty()) {
                errors.put(key, NOT_FOR_SALE);
            } else if (companies.size() > 1) {
                // No lo puede arreglar quien compra: es configuración. Se avisa para que alguien deje un solo mapeo.
                errors.put(key, NOT_FOR_SALE);
                reportAmbiguousMapping(saleLines.get(index), companies);
            } else {
                Long company = companies.iterator().next();
                if (orderCompany == null) {
                    orderCompany = company;
                } else if (!orderCompany.equals(company)) {
                    errors.put(key, SEPARATE_ORDER);
                }
            }
        }
        return errors;
    }

    // La misma condición con la que se envía la venta (PosIntegrationPolicy) y con la que se avisa de una
    // cobertura incompleta (una conexión activa). Sin las dos, el carrito funciona como antes de GAP-06.
    private boolean isActiveFor(Store store) {
        return posPolicy.isEnabledFor(store) && posConnectionRepository.existsActiveForStore(store.getId());
    }

    // null en la posición de una línea que no se puede resolver en esta tienda. El filtro de tienda importa: los
    // mapeos se buscan por producto, sin tienda, y el producto de otra podría fijar la empresa del pedido.
    private List<SaleLine> toSaleLines(Store store, List<CartLineCommand> lines) {
        Set<Long> productIds = lines.stream()
                .filter(line -> line.variationId() == null)
                .map(CartLineCommand::productId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> storeProducts = productIds.isEmpty() ? Set.of()
                : productRepository.findAllById(productIds).stream()
                        .filter(product -> product.getStore().getId().equals(store.getId()))
                        .map(Product::getId)
                        .collect(Collectors.toSet());
        Set<Long> variationIds = lines.stream()
                .map(CartLineCommand::variationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductVariation> variations = variationIds.isEmpty() ? Map.of()
                : variationRepository.findAllById(variationIds).stream()
                        .filter(variation -> variation.getStore().getId().equals(store.getId()))
                        .collect(Collectors.toMap(ProductVariation::getId, Function.identity()));

        List<SaleLine> saleLines = new ArrayList<>();
        for (CartLineCommand line : lines) {
            if (line.variationId() != null) {
                ProductVariation variation = variations.get(line.variationId());
                saleLines.add(variation == null ? null
                        : new SaleLine(variation.getProduct().getId(), variation.getId()));
            } else {
                saleLines.add(storeProducts.contains(line.productId()) ? new SaleLine(line.productId(), null) : null);
            }
        }
        return saleLines;
    }

    private static void reportAmbiguousMapping(SaleLine line, Set<Long> companies) {
        String message = "Producto %d%s mapeado a %d empresas del POS (%s): no se vende hasta dejar un solo mapeo"
                .formatted(line.productId(),
                        line.variationId() == null ? "" : " (variación " + line.variationId() + ")",
                        companies.size(), companies.stream().sorted().toList());
        log.warn(message);
        Sentry.captureMessage(message);
    }
}
