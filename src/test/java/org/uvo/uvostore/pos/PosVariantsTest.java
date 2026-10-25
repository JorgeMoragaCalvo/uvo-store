package org.uvo.uvostore.pos;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Attribute;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.AttributeType;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.AttributeRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.service.metrics.BusinessMetrics;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosSaleRequest;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.PosVariantFields;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.service.pos.SyncProductResult;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-5. Un producto con variantes en el POS (tallas, colores) se vende por variante en la web: se crea con sus
 * variaciones, su stock y su precio se mueven por variante, y la venta le dice al POS qué variante se vendió.
 *
 * <p>El modelo es el usual, padre con variantes, y el contrato es una propuesta (POS-1), como la venta y la
 * lectura masiva. La conciliación por variante está en {@code PosReconciliationTest} y el orden de cerrojos frente a
 * la venta, en {@code PosVariantLockOrderTest}.
 */
@WithPosIntegration
class PosVariantsTest extends IntegrationTestSupport {

    private static final String API_KEY = "clave-pos5";

    @Autowired
    private PosSyncService posSyncService;
    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PosOrderNotifier notifier;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private ProductVariationSyncMappingRepository variantMappingRepository;
    @Autowired
    private ProductVariationRepository variationRepository;
    @Autowired
    private AttributeRepository attributeRepository;
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;
    @Autowired
    private OrderRepository orderRepository;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private MeterRegistry meterRegistry;

    private Store store;
    private PosConnection connection;

    @BeforeEach
    void posStore() {
        store = createStore("pos5");
        setSetting(store, "pos_sync_enabled", "true");
        connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey(API_KEY);
        connection.setWebhookSecret("s");
        connection.setActive(true);
        connectionRepository.save(connection);
    }

    // --- altas -------------------------------------------------------------------------------------

    @Test
    @DisplayName("Un producto con variantes se crea variable, con sus variaciones y atributos, y la tienda lo muestra")
    void aVariableProductIsCreatedWithItsVariants() throws Exception {
        // La tienda ya tenía el atributo "Talla": se reutiliza, no se duplica.
        Attribute size = new Attribute();
        size.setStore(store);
        size.setName("Talla");
        size.setSlug("talla");
        size.setType(AttributeType.SELECT);
        attributeRepository.save(size);
        long externalId = nextSeq();

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content("""
                                {"companyId":%d,"externalId":%d,"sku":"POL-%d","name":"Polera %d","version":1,
                                 "categoryName":"Poleras","variants":[
                                  {"variantId":501,"sku":"POL-M-%d","price":9990,"stock":4,
                                   "attributes":{"Talla":"M","Color":"Azul"}},
                                  {"variantId":502,"sku":"POL-L-%d","price":10990,"stock":2,
                                   "attributes":{"Talla":"L","Color":"Azul"}}]}"""
                                .formatted(connection.getCompanyId(), externalId, externalId, externalId, externalId,
                                        externalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        Product product = productRepository.findByStoreIdAndSlug(store.getId(), "polera-" + externalId).orElseThrow();
        assertThat(product.getProductType()).isEqualTo(ProductType.VARIABLE);
        assertThat(product.getPrice()).as("el padre muestra el precio de la más barata").isEqualByComparingTo("9990");
        assertThat(product.getStock()).as("y la suma de sus stocks").isEqualTo(6);
        assertThat(variationRepository.findByProductId(product.getId())).hasSize(2);
        ProductSyncMapping mapping = mappingRepository
                .findByExternalIdAndCompanyId(externalId, connection.getCompanyId()).orElseThrow();
        assertThat(variantMappingRepository.findByMappingId(mapping.getId()))
                .extracting(variant -> variant.getExternalVariantId())
                .containsExactlyInAnyOrder(501L, 502L);
        assertThat(attributeRepository.findByStoreIdOrderByNameAsc(store.getId()))
                .extracting(Attribute::getSlug)
                .containsExactly("color", "talla");

        mockMvc.perform(get("/api/v1/products/polera-" + externalId).header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variations.length()").value(2))
                .andExpect(jsonPath("$.variations[?(@.sku == 'POL-M-%d')].attributes.Talla".formatted(externalId))
                        .value(contains("M")))
                .andExpect(jsonPath("$.variations[?(@.sku == 'POL-L-%d')].attributes.Color".formatted(externalId))
                        .value(contains("Azul")));
    }

    static Stream<Arguments> inconsistentVariants() {
        return Stream.of(
                Arguments.of("atributos distintos", """
                        [{"variantId":1,"sku":"A","price":1000,"stock":1,"attributes":{"Talla":"M"}},
                         {"variantId":2,"sku":"B","price":1000,"stock":1,"attributes":{"Color":"Azul"}}]""",
                        "mismos atributos"),
                Arguments.of("misma combinación", """
                        [{"variantId":1,"sku":"A","price":1000,"stock":1,"attributes":{"Talla":"M"}},
                         {"variantId":2,"sku":"B","price":1000,"stock":1,"attributes":{"talla":"m"}}]""",
                        "misma combinación"),
                Arguments.of("id repetido", """
                        [{"variantId":1,"sku":"A","price":1000,"stock":1,"attributes":{"Talla":"M"}},
                         {"variantId":1,"sku":"B","price":1000,"stock":1,"attributes":{"Talla":"L"}}]""",
                        "repetida"),
                Arguments.of("sin atributos", """
                        [{"variantId":1,"sku":"A","price":1000,"stock":1,"attributes":{}}]""",
                        "attributes"),
                Arguments.of("lista vacía", "[]", "vacía"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentVariants")
    @DisplayName("Una lista de variantes que la tienda no podría vender se rechaza con 400 y no crea nada")
    void inconsistentVariantsAreRejected(String label, String variants, String message) throws Exception {
        long externalId = nextSeq();

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content("""
                                {"companyId":%d,"externalId":%d,"sku":"MAL-%d","name":"Mal %d","version":1,
                                 "variants":%s}"""
                                .formatted(connection.getCompanyId(), externalId, externalId, externalId, variants)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(message)));

        assertThat(productRepository.findByStoreIdAndSlug(store.getId(), "mal-" + externalId)).isEmpty();
        assertThat(mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())).isEmpty();
    }

    @Test
    @DisplayName("Un producto sin variantes sigue necesitando precio y stock")
    void aSimpleProductStillNeedsPriceAndStock() throws Exception {
        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content("""
                                {"companyId":%d,"externalId":%d,"sku":"S-1","name":"Simple","version":1}"""
                                .formatted(connection.getCompanyId(), nextSeq())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("price y stock")));
    }

    // --- catálogo ------------------------------------------------------------------------------------

    @Test
    @DisplayName("El precio de una variante sigue al POS, y el del padre a la más barata")
    void aVariantPriceFollowsThePos() {
        Fixture fixture = variable();

        SyncProductResult result = posSyncService.syncProduct(connection, sync(fixture, 2,
                variant(fixture.m, "POL-M", 8990, null, "M"), variant(fixture.l, "POL-L", 10990, null, "L")));

        assertThat(result.applied()).isTrue();
        fresh();
        assertThat(variation(fixture, fixture.m).getPrice()).isEqualByComparingTo("8990");
        assertThat(product(fixture).getPrice()).isEqualByComparingTo("8990");
        assertThat(variation(fixture, fixture.m).getStock()).as("el stock no entra por aquí").isEqualTo(4);
    }

    @Test
    @DisplayName("El precio de una variante respeta el flag del mapeo y la versión")
    void aVariantPriceRespectsTheFlagAndTheVersion() {
        Fixture fixture = variable();
        ProductSyncMapping mapping = mapping(fixture);
        mapping.setSyncPrice(false);
        mappingRepository.save(mapping);

        posSyncService.syncProduct(connection, sync(fixture, 2,
                variant(fixture.m, "POL-M", 1, null, "M"), variant(fixture.l, "POL-L", 10990, null, "L")));
        fresh();
        assertThat(variation(fixture, fixture.m).getPrice()).as("sin syncPrice, el precio es de la web")
                .isEqualByComparingTo("9990");

        mapping = mapping(fixture);
        mapping.setSyncPrice(true);
        mappingRepository.save(mapping);
        SyncWebhookLog older = posWebhookService.handleProductUpdated(productUpdated(fixture, 1,
                variant(fixture.m, "POL-M", 1, null, "M"), variant(fixture.l, "POL-L", 10990, null, "L")));
        fresh();
        assertThat(older.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(variation(fixture, fixture.m).getPrice()).as("una versión vieja no se aplica")
                .isEqualByComparingTo("9990");
    }

    @Test
    @DisplayName("Una variante nueva en el POS se crea, una que el POS quitó se desactiva, y si vuelve se reactiva")
    void aNewVariantIsCreatedAndAMissingOneIsDeactivated() {
        Fixture fixture = variable();
        long xl = nextSeq();

        SyncWebhookLog log = posWebhookService.handleProductUpdated(productUpdated(fixture, 2,
                variant(fixture.m, "POL-M", 9990, null, "M"), variant(xl, "POL-XL-" + xl, 11990, 3, "XL")));

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        fresh();
        ProductVariation large = variation(fixture, fixture.l);
        assertThat(large.isActive()).as("la L ya no está en el POS").isFalse();
        assertThat(large.getStock()).as("pero su stock no se toca").isEqualTo(2);
        ProductVariation extraLarge = variation(fixture, xl);
        assertThat(extraLarge.isActive()).isTrue();
        assertThat(extraLarge.getStock()).isEqualTo(3);
        assertThat(extraLarge.getAttributeAssignments()).hasSize(2);
        assertThat(product(fixture).getStock()).as("el padre suma todas, como recalculateStockFromVariations")
                .isEqualTo(4 + 2 + 3);
        assertThat(product(fixture).getPrice()).as("el precio del padre solo mira las activas")
                .isEqualByComparingTo("9990");

        posWebhookService.handleProductUpdated(productUpdated(fixture, 3,
                variant(fixture.m, "POL-M", 9990, null, "M"), variant(fixture.l, "POL-L", 10990, null, "L"),
                variant(xl, "POL-XL-" + xl, 11990, null, "XL")));
        fresh();
        assertThat(variation(fixture, fixture.l).isActive()).as("la L volvió al POS").isTrue();
        assertThat(variationRepository.findByProductId(fixture.productId)).hasSize(3);
    }

    @Test
    @DisplayName("Un producto simple que llega con variantes no se convierte: queda registrado para revisión")
    void variantsForASimpleProductFail() {
        long externalId = nextSeq();
        SyncProductResult created = posSyncService.syncProduct(connection, new SyncProductCommand(externalId,
                "SIM-" + externalId, "Simple " + externalId, null, BigDecimal.valueOf(5000), 5, null, null, true, 1,
                null));

        SyncProductResult result = posSyncService.syncProduct(connection, new SyncProductCommand(externalId,
                "SIM-" + externalId, "Simple " + externalId, null, null, null, null, null, true, 2,
                List.of(variant(nextSeq(), "SIM-M", 5000, 2, "M"))));

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("simple").contains("revisión manual");
        assertThat(webhookLogRepository.findById(result.logId()).orElseThrow().getStatus())
                .isEqualTo(WebhookStatus.FAILED);
        fresh();
        Product product = productRepository.findById(created.productId()).orElseThrow();
        assertThat(product.getProductType()).isEqualTo(ProductType.SIMPLE);
        assertThat(variationRepository.findByProductId(product.getId())).isEmpty();
        assertThat(mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())
                .orElseThrow().getCatalogVersion()).as("la versión no avanza con lo que no se aplicó").isEqualTo(1L);
    }

    // --- stock ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("stock.updated de una variante mueve solo esa variante, y el padre vuelve a sumar")
    void stockUpdatedForAVariant() {
        Fixture fixture = variable();

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stockUpdated(fixture, fixture.m, 4, 1));

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        fresh();
        assertThat(variation(fixture, fixture.m).getStock()).isEqualTo(1);
        assertThat(variation(fixture, fixture.l).getStock()).isEqualTo(2);
        assertThat(product(fixture).getStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("Una variante que se movió desde que el POS la leyó no se pisa, y un evento repetido se descarta")
    void aVariantStockEventFollowsTheSameRulesAsAProduct() {
        Fixture fixture = variable();
        double before = webhookDivergences();

        SyncWebhookLog stale = posWebhookService.handleStockUpdated(stockUpdated(fixture, fixture.m, 7, 1));
        assertThat(stale.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(stale.getErrorMessage()).contains("Divergencia").contains("variación");
        // PROD-15b: la divergencia cuenta; el repetido de abajo no, porque no lo es.
        assertThat(webhookDivergences() - before).isEqualTo(1);

        posWebhookService.handleStockUpdated(stockUpdated(fixture, fixture.m, 4, 1));
        SyncWebhookLog repeated = posWebhookService.handleStockUpdated(stockUpdated(fixture, fixture.m, 4, 1));
        assertThat(repeated.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(webhookDivergences() - before).isEqualTo(1);
        fresh();
        assertThat(variation(fixture, fixture.m).getStock()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un stock.updated sin variante para un producto variable no toca nada")
    void stockUpdatedWithoutVariantForAVariableProductFails() {
        Fixture fixture = variable();

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stockUpdated(fixture, null, 6, 0));

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(log.getErrorMessage()).contains("variantId");
        fresh();
        assertThat(product(fixture).getStock()).as("el padre no se escribe: es la suma de sus variantes")
                .isEqualTo(6);
        assertThat(variation(fixture, fixture.m).getStock()).isEqualTo(4);

        SyncWebhookLog unknown = posWebhookService.handleStockUpdated(stockUpdated(fixture, nextSeq(), 4, 0));
        assertThat(unknown.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(unknown.getErrorMessage()).contains("no está mapeada");
    }

    // --- venta ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("La venta le dice al POS qué variante se vendió, y no envía una variación sin mapear")
    void theSaleCarriesTheVariant() {
        Fixture fixture = variable();
        Product product = product(fixture);
        // Una variación que el POS no conoce (no debería existir: el panel no las crea en un producto mapeado).
        ProductVariation stray = new ProductVariation();
        stray.setStore(store);
        stray.setProduct(product);
        stray.setSku("SUELTA-" + nextSeq());
        stray.setPrice(BigDecimal.valueOf(9990));
        stray.setStock(1);
        variationRepository.save(stray);

        Order order = paidOrder(product, variation(fixture, fixture.m), stray);

        assertThat(notifier.companiesToNotify(order)).containsExactly(connection.getCompanyId());
        PosSaleRequest request = notifier.prepare(order.getId(), connection.getCompanyId());

        assertThat(request.kind()).isEqualTo(PosSaleRequest.Kind.READY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.payload().body().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst())
                .containsEntry("product_id", fixture.externalId)
                .containsEntry("variant_id", fixture.m)
                .containsEntry("quantity", 1);
    }

    @Test
    @DisplayName("La respuesta del POS se compara por variante: el padre no da una divergencia falsa")
    void aVariantResultDoesNotRaiseAFalseDivergence() {
        Fixture fixture = variable();
        Logger notifierLogger = (Logger) LoggerFactory.getLogger(PosOrderNotifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        notifierLogger.addAppender(logs);
        try {
            // La M tiene 4 aquí y en el POS. El padre suma 6: compararlo con un resultado avisaría en cada venta.
            notifier.checkStockDivergence(response(
                    Map.of("success", true, "product_id", fixture.externalId, "variant_id", fixture.m,
                            "new_stock_web", 4),
                    Map.of("success", true, "product_id", fixture.externalId, "new_stock_web", 4)),
                    connection.getCompanyId());
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("Divergencia"));

            notifier.checkStockDivergence(response(
                    Map.of("success", true, "product_id", fixture.externalId, "variant_id", fixture.l,
                            "new_stock_web", 0)),
                    connection.getCompanyId());
            assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("Divergencia")
                    && event.getFormattedMessage().contains("la variación"));
        } finally {
            notifierLogger.detachAppender(logs);
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private double webhookDivergences() {
        return meterRegistry.get(BusinessMetrics.STOCK_DIVERGENCES).tag("source", "webhook").counter().count();
    }

    /** Un producto del POS con dos variantes: M (9990, stock 4) y L (10990, stock 2), en la versión 1. */
    private record Fixture(Long productId, long externalId, long m, long l) {
    }

    private Fixture variable() {
        long externalId = nextSeq();
        long m = nextSeq();
        long l = nextSeq();
        SyncProductResult result = posSyncService.syncProduct(connection, new SyncProductCommand(externalId,
                "POL-" + externalId, "Polera " + externalId, "Del POS", null, null, null, null, true, 1,
                List.of(variant(m, "POL-M", 9990, 4, "M"), variant(l, "POL-L", 10990, 2, "L"))));
        assertThat(result.created()).isTrue();
        return new Fixture(result.productId(), externalId, m, l);
    }

    // El SKU lleva el id de la variante: los SKU de variación son únicos por tienda.
    private static PosVariantFields variant(long id, String sku, long price, Integer stock, String size) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Talla", size);
        attributes.put("Color", "Azul");
        return new PosVariantFields(id, sku + "-" + id, BigDecimal.valueOf(price), stock, attributes);
    }

    private SyncProductCommand sync(Fixture fixture, long version, PosVariantFields... variants) {
        return new SyncProductCommand(fixture.externalId, "POL-" + fixture.externalId, "Polera " + fixture.externalId,
                null, null, null, null, null, true, version, List.of(variants));
    }

    /** {@code product.updated} con las variantes en {@code data}, como las manda el POS: mapas en camelCase. */
    private ProductUpdatePayload productUpdated(Fixture fixture, long version, PosVariantFields... variants) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (PosVariantFields variant : variants) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("variantId", variant.variantId());
            entry.put("sku", variant.sku());
            entry.put("price", variant.price());
            entry.put("stock", variant.stock());
            entry.put("attributes", variant.attributes());
            data.add(entry);
        }
        return new ProductUpdatePayload(connection.getCompanyId(), fixture.externalId, "POL", version,
                Map.of("variants", data));
    }

    private StockUpdatePayload stockUpdated(Fixture fixture, Long variantId, int oldStock, int stockWeb) {
        return new StockUpdatePayload(connection.getCompanyId(), fixture.externalId, variantId, "POL", oldStock,
                stockWeb, stockWeb);
    }

    @SafeVarargs
    private static Map<String, Object> response(Map<String, Object>... results) {
        return Map.of("data", Map.of("results", List.of(results)));
    }

    /** Una orden cobrada con una unidad de cada variación. */
    private Order paidOrder(Product product, ProductVariation... variations) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-POS5-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(9990L * variations.length));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(order.getSubtotal());
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        List<OrderItem> items = new ArrayList<>();
        for (ProductVariation variation : variations) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setVariation(variation);
            item.setProductName(product.getName());
            item.setProductSku(variation.getSku());
            item.setQuantity(1);
            item.setPrice(variation.getPrice());
            item.setSubtotal(variation.getPrice());
            item.setTaxAmount(BigDecimal.ZERO);
            items.add(item);
        }
        order.setItems(items);
        return orderRepository.save(order);
    }

    /** Lo que quedó en la base: las escrituras condicionadas limpian la sesión, y el escritor no vuelca solo. */
    private void fresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private Product product(Fixture fixture) {
        return productRepository.findById(fixture.productId).orElseThrow();
    }

    private ProductSyncMapping mapping(Fixture fixture) {
        return mappingRepository.findByExternalIdAndCompanyId(fixture.externalId, connection.getCompanyId())
                .orElseThrow();
    }

    private ProductVariation variation(Fixture fixture, long variantId) {
        return variantMappingRepository.findByMappingIdAndExternalVariantId(mapping(fixture).getId(), variantId)
                .orElseThrow()
                .getVariation();
    }
}
