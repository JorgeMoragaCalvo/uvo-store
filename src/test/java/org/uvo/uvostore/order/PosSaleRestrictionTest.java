package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-06. Con el POS activo, una orden es de una sola empresa y no lleva productos sin mapeo.
 *
 * <p>Antes, una orden de dos empresas generaba dos documentos y cada uno llevaba los totales de la orden completa;
 * y una línea sin mapeo se cobraba sin llegar a ningún documento. Ahora el carrito ({@code /cart/validate}) y el
 * checkout las rechazan por línea, con la misma forma que un problema de stock, y así cada documento coincide con
 * la orden cobrada. Con el POS apagado, nada de esto aplica.
 */
@WithPosIntegration
class PosSaleRestrictionTest extends IntegrationTestSupport {

    private static final String NOT_FOR_SALE = "Este producto no está disponible para la venta en línea";
    private static final String SEPARATE_ORDER = "Este producto se compra en un pedido aparte";

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private ProductVariationSyncMappingRepository variantMappingRepository;
    @Autowired
    private ProductVariationRepository variationRepository;

    @Test
    @DisplayName("Productos de una sola empresa: el checkout pasa")
    void oneCompanyGoesThrough() throws Exception {
        Store store = posStore("gap06-one");
        long company = connect(store);
        Product first = mapped(store, company);
        Product second = mapped(store, company);

        mockMvc.perform(checkout(store, line(first), line(second)))
                .andExpect(status().isOk());

        assertThat(ordersOf(store)).isEqualTo(1);
    }

    @Test
    @DisplayName("Productos de dos empresas: 409 en la línea de la segunda, y no nace ninguna orden")
    void twoCompaniesAreRejected() throws Exception {
        Store store = posStore("gap06-two");
        Product first = mapped(store, connect(store));
        Product second = mapped(store, connect(store));

        mockMvc.perform(checkout(store, line(first), line(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Algunos productos no se pueden comprar en este pedido. Revisa el detalle de cada uno."))
                .andExpect(jsonPath("$.fieldErrors['items.0']").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors['items.1']").value(SEPARATE_ORDER));

        assertThat(ordersOf(store)).isZero();
    }

    @Test
    @DisplayName("Un producto sin mapeo no se vende")
    void anUnmappedProductIsNotSold() throws Exception {
        Store store = posStore("gap06-unmapped");
        Product mappedProduct = mapped(store, connect(store));
        Product panelProduct = product(store);

        mockMvc.perform(checkout(store, line(mappedProduct), line(panelProduct)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors['items.1']").value(NOT_FOR_SALE));

        assertThat(ordersOf(store)).isZero();
    }

    @Test
    @DisplayName("Una variante sin mapeo de un producto mapeado tampoco: el POS descontaría otra cosa")
    void anUnmappedVariantIsNotSold() throws Exception {
        Store store = posStore("gap06-variant");
        long company = connect(store);
        Product product = mapped(store, company);
        ProductVariation mappedVariant = variation(store, product);
        variantMapping(mappingOf(product, company), mappedVariant);
        ProductVariation strayVariant = variation(store, product);

        mockMvc.perform(checkout(store, variationLine(mappedVariant), variationLine(strayVariant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors['items.0']").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors['items.1']").value(NOT_FOR_SALE));

        mockMvc.perform(checkout(store, variationLine(mappedVariant)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un producto mapeado a dos empresas no se vende: entraría en los dos documentos")
    void aProductMappedToTwoCompaniesIsNotSold() throws Exception {
        Store store = posStore("gap06-ambiguous");
        Product product = mapped(store, connect(store));
        mapping(product, connect(store));

        mockMvc.perform(checkout(store, line(product)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors['items.0']").value(NOT_FOR_SALE));

        assertThat(ordersOf(store)).isZero();
    }

    @Test
    @DisplayName("El carrito ve lo mismo antes de pagar")
    void theCartSeesItFirst() throws Exception {
        Store store = posStore("gap06-cart");
        Product first = mapped(store, connect(store));
        Product second = mapped(store, connect(store));
        Product panelProduct = product(store);

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"items\":[%s]}".formatted(String.join(",",
                                line(first), line(second), line(panelProduct)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['items.0']").doesNotExist())
                .andExpect(jsonPath("$.errors['items.1']").value(SEPARATE_ORDER))
                .andExpect(jsonPath("$.errors['items.2']").value(NOT_FOR_SALE));
    }

    @Test
    @DisplayName("Si la línea además no tiene stock, se explica el stock")
    void aStockProblemComesFirst() throws Exception {
        Store store = posStore("gap06-stock");
        Product first = mapped(store, connect(store));
        Product second = mapped(store, connect(store)); // stock 10

        mockMvc.perform(checkout(store, line(first), line(second, 11)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Uno o más productos no tienen stock suficiente"))
                .andExpect(jsonPath("$.fieldErrors['items.1']").value("Stock insuficiente. Solo hay 10 disponibles"));
    }

    @Test
    @DisplayName("Con la sincronización de la tienda apagada, la mezcla y lo no mapeado se venden como antes")
    void withTheStoreSwitchOffNothingChanges() throws Exception {
        Store store = createStore("gap06-off");
        disableShipping(store);
        setSetting(store, "pos_sync_enabled", "false");
        Product first = mapped(store, connect(store));
        Product second = mapped(store, connect(store));
        Product panelProduct = product(store);

        mockMvc.perform(checkout(store, line(first), line(second), line(panelProduct)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sin una conexión activa, tampoco aplica: no se enviaría nada")
    void withoutAnActiveConnectionNothingChanges() throws Exception {
        Store store = posStore("gap06-inactive");
        long company = connect(store);
        PosConnection connection = connectionRepository.findByCompanyId(company).orElseThrow();
        connection.setActive(false);
        connectionRepository.save(connection);
        Product mappedProduct = mapped(store, company);
        Product panelProduct = product(store);

        mockMvc.perform(checkout(store, line(mappedProduct), line(panelProduct)))
                .andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Store posStore(String slugPrefix) {
        Store store = createStore(slugPrefix);
        disableShipping(store);
        setSetting(store, "pos_sync_enabled", "true");
        return store;
    }

    /** Una conexión activa de una empresa nueva; devuelve su company_id. */
    private long connect(Store store) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("pos-key-" + nextSeq());
        connection.setWebhookSecret("s");
        connection.setActive(true);
        return connectionRepository.save(connection).getCompanyId();
    }

    private Product product(Store store) {
        Category category = createCategory(store, "Cat");
        return createProduct(store, category, "Producto", BigDecimal.valueOf(10000));
    }

    private Product mapped(Store store, long companyId) {
        Product product = product(store);
        mapping(product, companyId);
        return product;
    }

    private ProductSyncMapping mapping(Product product, long companyId) {
        long externalId = nextSeq();
        return mappingRepository.save(ProductSyncMapping.builder()
                .product(product)
                .companyId(companyId)
                .externalId(externalId)
                .externalSku("EXT-" + externalId)
                .build());
    }

    private ProductSyncMapping mappingOf(Product product, long companyId) {
        return mappingRepository.findByProductIdAndCompanyId(product.getId(), companyId).orElseThrow();
    }

    private ProductVariation variation(Store store, Product product) {
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku("VAR-" + nextSeq());
        variation.setPrice(BigDecimal.valueOf(9990));
        variation.setStock(5);
        return variationRepository.save(variation);
    }

    private void variantMapping(ProductSyncMapping mapping, ProductVariation variation) {
        ProductVariationSyncMapping variant = new ProductVariationSyncMapping();
        variant.setMapping(mapping);
        variant.setVariation(variation);
        variant.setExternalVariantId(nextSeq());
        variant.setExternalSku("EXT-VAR-" + variation.getId());
        variantMappingRepository.save(variant);
    }

    private long ordersOf(Store store) {
        return orderRepository.findAll().stream()
                .filter(order -> order.getStore().getId().equals(store.getId()))
                .count();
    }

    private static String line(Product product) {
        return line(product, 1);
    }

    private static String line(Product product, int quantity) {
        return "{\"id\":%d,\"type\":\"product\",\"quantity\":%d}".formatted(product.getId(), quantity);
    }

    private static String variationLine(ProductVariation variation) {
        return "{\"id\":%d,\"type\":\"variation\",\"quantity\":1}".formatted(variation.getId());
    }

    private MockHttpServletRequestBuilder checkout(Store store, String... lines) {
        String body = """
                {
                  "customer": {"email":"gap06-%d@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [%s],
                  "paymentMethod": "manual"
                }
                """.formatted(nextSeq(), Arrays.stream(lines).collect(Collectors.joining(",")));
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body);
    }
}
