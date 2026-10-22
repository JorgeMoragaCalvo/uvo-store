package org.uvo.uvostore.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.ProductImageRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-2. El panel no edita lo que gestiona UvoPOS.
 *
 * <p>Antes el panel no consultaba {@code ProductSyncMapping}: el precio o el stock de un producto sincronizado
 * se podían cambiar, el cambio no llegaba al POS y la siguiente sincronización lo pisaba. Ahora cada flag del
 * mapeo bloquea lo mismo que escribe el webhook {@code product.updated}, el SKU se bloquea con cualquier mapeo
 * activo, y el resto del producto sigue siendo de la web.
 */
class PosOwnedProductFieldsTest extends IntegrationTestSupport {

    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private ProductVariationRepository variationRepository;
    @Autowired
    private ProductImageRepository productImageRepository;

    private Store store;
    private Category category;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("pos-owned");
        category = createCategory(store, "Cat");
        User admin = createAdmin(store, "pos-owned");
        token = loginAdmin(store, admin);
    }

    // --- campos bloqueados -------------------------------------------------------------------------

    @Test
    @DisplayName("Un producto con el precio del POS rechaza un precio nuevo")
    void aMappedProductRejectsAPriceChange() throws Exception {
        Product product = product();
        map(product, Flags.PRICE_AND_STOCK);

        Map<String, String> fields = formFields(product);
        fields.put("price", "12990");
        update(product, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Este producto se sincroniza con UvoPOS: el precio se cambia en el POS, no en el panel."));

        assertThat(product.getPrice()).isEqualByComparingTo("9990");
    }

    @Test
    @DisplayName("Un producto con el stock del POS rechaza un stock nuevo")
    void aMappedProductRejectsAStockChange() throws Exception {
        Product product = product();
        map(product, Flags.PRICE_AND_STOCK);

        Map<String, String> fields = formFields(product);
        fields.put("stock", "3");
        update(product, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("el stock se cambia en el POS")));

        assertThat(product.getStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("La gestión de stock va con el flag del stock")
    void manageStockFollowsTheStockFlag() throws Exception {
        // Sin gestión de stock la tienda vendería sin límite lo que cuenta el POS.
        Product stockSynced = product();
        map(stockSynced, Flags.PRICE_AND_STOCK);
        Map<String, String> fields = formFields(stockSynced);
        fields.put("manageStock", "false");
        update(stockSynced, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("la gestión de stock")));
        assertThat(stockSynced.isManageStock()).isTrue();

        Product priceOnly = product();
        map(priceOnly, new Flags(true, false, false, false));
        Map<String, String> priceOnlyFields = formFields(priceOnly);
        priceOnlyFields.put("manageStock", "false");
        update(priceOnly, priceOnlyFields).andExpect(status().isOk());
        assertThat(priceOnly.isManageStock()).isFalse();
    }

    @Test
    @DisplayName("El SKU se bloquea con cualquier mapeo activo, aunque no tenga ningún flag")
    void theSkuIsLockedByAnyActiveMappingEvenWithAllFlagsOff() throws Exception {
        Product product = product();
        map(product, Flags.NONE);

        Map<String, String> fields = formFields(product);
        fields.put("sku", "OTRO-SKU");
        update(product, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("el SKU se cambia en el POS")));

        // Sin flags, el precio sigue siendo de la web.
        Map<String, String> priceChange = formFields(product);
        priceChange.put("price", "12990");
        update(product, priceChange).andExpect(status().isOk());
        assertThat(product.getPrice()).isEqualByComparingTo("12990");
    }

    @Test
    @DisplayName("El nombre y la descripción se bloquean solo con sus flags")
    void nameAndDescriptionAreLockedOnlyWhenTheirFlagsAreOn() throws Exception {
        Product locked = product();
        map(locked, Flags.ALL);
        Map<String, String> nameChange = formFields(locked);
        nameChange.put("name", "Nombre nuevo");
        update(locked, nameChange)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("el nombre se cambia en el POS")));
        Map<String, String> descriptionChange = formFields(locked);
        descriptionChange.put("description", "Descripción nueva");
        update(locked, descriptionChange)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("la descripción se cambia en el POS")));

        // Con los valores de fábrica (precio y stock), el nombre y la descripción son de la web.
        Product open = product();
        map(open, Flags.PRICE_AND_STOCK);
        Map<String, String> fields = formFields(open);
        fields.put("name", "Nombre web");
        fields.put("description", "Descripción web");
        update(open, fields).andExpect(status().isOk());
        assertThat(open.getName()).isEqualTo("Nombre web");
        assertThat(open.getSlug()).isEqualTo("nombre-web");
        assertThat(open.getDescription()).isEqualTo("Descripción web");
    }

    @Test
    @DisplayName("La descripción corta sigue siendo de la web aunque la descripción sea del POS")
    void theShortDescriptionStaysEditableWithDescriptionLocked() throws Exception {
        // La "descripción comercial" del criterio de aceptación: el webhook nunca escribe shortDescription.
        Product product = product();
        map(product, Flags.ALL);

        Map<String, String> fields = webOnly(formFields(product));
        fields.put("shortDescription", "Texto comercial");
        update(product, fields).andExpect(status().isOk());

        assertThat(product.getShortDescription()).isEqualTo("Texto comercial");
        assertThat(product.getDescription()).isEqualTo("Descripción del POS");
    }

    @Test
    @DisplayName("El rechazo nombra todos los campos del POS que se intentaron cambiar")
    void theRejectionNamesEveryChangedLockedField() throws Exception {
        Product product = product();
        map(product, Flags.ALL);

        Map<String, String> fields = formFields(product);
        fields.put("name", "Otro nombre");
        fields.put("price", "1");
        fields.put("stock", "1");
        update(product, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Este producto se sincroniza con UvoPOS: el nombre, el precio y el stock se cambian en el "
                                + "POS, no en el panel."));
    }

    @Test
    @DisplayName("Reenviar los valores guardados no es un cambio")
    void resendingTheStoredValuesIsAccepted() throws Exception {
        // Para quien manda el producto entero: 9990 es el mismo precio que 9990.00, y "" el mismo vacío que null.
        Product product = product();
        product.setPrice(new BigDecimal("9990.00"));
        product.setDescription(null);
        productRepository.save(product);
        map(product, Flags.ALL);

        Map<String, String> fields = formFields(product);
        fields.put("price", "9990");
        fields.put("description", "");
        update(product, fields).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un campo del POS que no se envía conserva su valor")
    void omittedLockedFieldsKeepTheirValues() throws Exception {
        // Es lo que hace el formulario: no envía lo bloqueado, porque un valor leído al cargar la página pudo
        // cambiar por una venta o por el POS.
        Product product = product();
        String name = product.getName();
        String slug = product.getSlug();
        String sku = product.getSku();
        map(product, Flags.ALL);

        Map<String, String> fields = webOnly(formFields(product));
        fields.put("shortDescription", "Cambio web");
        update(product, fields).andExpect(status().isOk());

        assertThat(product.getName()).isEqualTo(name);
        assertThat(product.getSlug()).isEqualTo(slug);
        assertThat(product.getDescription()).isEqualTo("Descripción del POS");
        assertThat(product.getSku()).isEqualTo(sku);
        assertThat(product.getPrice()).isEqualByComparingTo("9990");
        assertThat(product.getStock()).isEqualTo(10);
        assertThat(product.isManageStock()).isTrue();
    }

    @Test
    @DisplayName("Con todo bloqueado, lo que es de la web se sigue editando")
    void webFieldsStayEditableOnAFullyMappedProduct() throws Exception {
        Product product = product();
        map(product, Flags.ALL);
        Category other = createCategory(store, "Otra");

        Map<String, String> fields = webOnly(formFields(product));
        fields.put("shortDescription", "Comercial");
        fields.put("isFeatured", "false");
        fields.put("active", "false");
        fields.put("categoryId", String.valueOf(other.getId()));
        update(product, fields, image()).andExpect(status().isOk());

        assertThat(product.getShortDescription()).isEqualTo("Comercial");
        assertThat(product.isFeatured()).isFalse();
        assertThat(product.isActive()).isFalse();
        assertThat(product.getCategory().getId()).isEqualTo(other.getId());
        assertThat(productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Un rechazo no guarda imágenes ni cambia nada del producto")
    void aRejectedUpdateStoresNoImagesAndChangesNothing() throws Exception {
        // La comprobación va antes de tocar el producto: después, la sesión ya tendría los cambios y las
        // imágenes ya estarían en disco.
        Product product = product();
        map(product, Flags.PRICE_AND_STOCK);

        Map<String, String> fields = formFields(product);
        fields.put("price", "1");
        fields.put("shortDescription", "No debería quedar");
        update(product, fields, image()).andExpect(status().isBadRequest());

        assertThat(product.getShortDescription()).isNull();
        assertThat(productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).isEmpty();
    }

    @Test
    @DisplayName("Un mapeo en pausa no bloquea nada, igual que no lo aplica el webhook")
    void aPausedMappingLocksNothing() throws Exception {
        Product product = product();
        map(product, Flags.ALL).setSyncStatus(SyncStatus.PAUSED);

        Map<String, String> fields = formFields(product);
        fields.put("price", "12990");
        fields.put("sku", "SKU-WEB");
        update(product, fields).andExpect(status().isOk());

        assertThat(product.getPrice()).isEqualByComparingTo("12990");
        assertThat(product.getSku()).isEqualTo("SKU-WEB");
    }

    @Test
    @DisplayName("Con dos empresas se bloquea la unión de sus flags")
    void twoCompaniesLockTheUnionOfTheirFlags() throws Exception {
        // Un producto puede estar mapeado a dos empresas (unique por producto y empresa, no por producto).
        Product product = product();
        map(product, new Flags(true, false, false, false));
        map(product, new Flags(false, true, false, false));

        ownership(product)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFields").value(contains("sku", "price", "stock", "manageStock")));

        Map<String, String> stockChange = formFields(product);
        stockChange.put("stock", "1");
        update(product, stockChange).andExpect(status().isBadRequest());
        Map<String, String> priceChange = formFields(product);
        priceChange.put("price", "1");
        update(product, priceChange).andExpect(status().isBadRequest());
    }

    // --- variantes ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Un producto sincronizado no admite variantes nuevas")
    void variationsCannotBeAddedToAMappedProduct() throws Exception {
        // Recalcular el agregado reescribiría el precio y el stock que gestiona el POS, y desde POS-5 las variantes
        // de un producto sincronizado son del POS.
        Product product = product();
        map(product, Flags.PRICE_AND_STOCK);

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variations")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(variationJson("V-NUEVA", 1000, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("sus variantes se crean y se cambian en el POS")));

        assertThat(variationRepository.findByProductId(product.getId())).isEmpty();
        assertThat(product.getPrice()).isEqualByComparingTo("9990");
        assertThat(product.getStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("Las variantes de un producto sincronizado no se editan")
    void variationsCannotBeUpdatedOnAMappedProduct() throws Exception {
        Product product = product();
        ProductVariation variation = variation(product);
        map(product, Flags.PRICE_AND_STOCK);

        mockMvc.perform(put("/api/admin/products/" + product.getId() + "/variations/" + variation.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(variationJson("V-EDIT", 1, 99)))
                .andExpect(status().isBadRequest());

        assertThat(variation.getPrice()).isEqualByComparingTo("1200");
        assertThat(variation.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("Las variantes de un producto sincronizado no se borran")
    void variationsCannotBeDeletedOnAMappedProduct() throws Exception {
        Product product = product();
        ProductVariation variation = variation(product);
        map(product, Flags.PRICE_AND_STOCK);

        mockMvc.perform(delete("/api/admin/products/" + product.getId() + "/variations/" + variation.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertThat(variationRepository.findById(variation.getId())).isPresent();
    }

    @Test
    @DisplayName("La guarda mira el producto de la variante, no el de la ruta")
    void theVariationGuardUsesTheVariationsRealParent() throws Exception {
        // El controlador busca la variante por su id e ignora el {productId} de la ruta: con la ruta de un
        // producto sin mapeo se llegaría a la variante de uno mapeado.
        Product unmapped = product();
        Product mapped = product();
        ProductVariation variation = variation(mapped);
        map(mapped, Flags.PRICE_AND_STOCK);

        mockMvc.perform(put("/api/admin/products/" + unmapped.getId() + "/variations/" + variation.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(variationJson("V-EDIT", 1, 99)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/admin/products/" + unmapped.getId() + "/variations/" + variation.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertThat(variation.getPrice()).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("Un producto sin mapeo sigue admitiendo variantes")
    void unmappedProductsStillAcceptVariations() throws Exception {
        Product product = product();

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variations")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(variationJson("V-WEB", 1000, 2)))
                .andExpect(status().isOk());

        assertThat(variationRepository.findByProductId(product.getId())).hasSize(1);
    }

    // --- lo que el guardado ya no borra ------------------------------------------------------------

    @Test
    @DisplayName("Guardar desde el panel no borra el posProductId")
    void posProductIdSurvivesAnAdminSave() throws Exception {
        // El formulario no lo envía, y cada guardado lo dejaba en null.
        Product product = product();
        product.setPosProductId("POS-77");
        productRepository.save(product);

        update(product, formFields(product)).andExpect(status().isOk());

        assertThat(product.getPosProductId()).isEqualTo("POS-77");
    }

    @Test
    @DisplayName("Sin gestión de stock en la petición: al crear vale sí, al actualizar se conserva")
    void omittingManageStockDefaultsOnCreateAndKeepsOnUpdate() throws Exception {
        // Era un primitivo: si faltaba, el multipart entero respondía 400 antes de llegar al servicio.
        String response = mockMvc.perform(multipart("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("productType", "simple")
                        .param("name", "Sin gestión explícita")
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "SIN-GESTION-" + nextSeq())
                        .param("price", "1000")
                        .param("stock", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long createdId = objectMapper.readTree(response).get("id").asLong();
        assertThat(productRepository.findById(createdId).orElseThrow().isManageStock()).isTrue();

        Product product = product();
        product.setManageStock(false);
        productRepository.save(product);
        Map<String, String> fields = formFields(product);
        fields.remove("manageStock");
        update(product, fields).andExpect(status().isOk());
        assertThat(product.isManageStock()).isFalse();
    }

    // --- GET /{id}/pos-ownership -------------------------------------------------------------------

    @Test
    @DisplayName("La consulta lista los campos bloqueados con los nombres del formulario")
    void productOwnershipListsLockedFieldsWithFormKeys() throws Exception {
        Product mapped = product();
        map(mapped, Flags.ALL);
        ownership(mapped)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFields").value(
                        contains("name", "description", "sku", "price", "stock", "manageStock")));

        ownership(product())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFields").value(empty()));
    }

    @Test
    @DisplayName("No se puede consultar un producto de otra tienda")
    void ownershipOfAnotherStoresProductIs404() throws Exception {
        Store other = createStore("pos-owned-other");
        Product foreign = createProduct(other, createCategory(other, "Cat"), "Ajeno", BigDecimal.TEN);
        map(foreign, Flags.ALL);

        ownership(foreign).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("La consulta pide products.view")
    void ownershipRequiresProductsView() throws Exception {
        Product product = product();

        String withoutPermission = loginAdmin(store, createAdminWithPermissions(store, "pos-owned-no", "orders.view"));
        mockMvc.perform(get("/api/admin/products/" + product.getId() + "/pos-ownership")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + withoutPermission))
                .andExpect(status().isForbidden());

        String viewer = loginAdmin(store, createAdminWithPermissions(store, "pos-owned-view", "products.view"));
        mockMvc.perform(get("/api/admin/products/" + product.getId() + "/pos-ownership")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Flags(boolean price, boolean stock, boolean name, boolean description) {
        static final Flags NONE = new Flags(false, false, false, false);
        // Los valores de fábrica de ProductSyncMapping y de PosSyncServiceImpl.
        static final Flags PRICE_AND_STOCK = new Flags(true, true, false, false);
        static final Flags ALL = new Flags(true, true, true, true);
    }

    private Product product() {
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(9990));
        product.setDescription("Descripción del POS");
        return productRepository.save(product);
    }

    // Con los cuatro flags explícitos: no dependen de los @Builder.Default de la entidad.
    private ProductSyncMapping map(Product product, Flags flags) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(nextSeq());
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(nextSeq());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncPrice(flags.price());
        mapping.setSyncStock(flags.stock());
        mapping.setSyncName(flags.name());
        mapping.setSyncDescription(flags.description());
        return mappingRepository.save(mapping);
    }

    private ProductVariation variation(Product product) {
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku("VAR-" + nextSeq());
        variation.setPrice(BigDecimal.valueOf(1200));
        variation.setStock(5);
        return variationRepository.save(variation);
    }

    /** Lo que envía el formulario del panel con el producto tal como está guardado. */
    private Map<String, String> formFields(Product product) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("productType", "simple");
        fields.put("name", product.getName());
        fields.put("shortDescription", product.getShortDescription() == null ? "" : product.getShortDescription());
        fields.put("description", product.getDescription() == null ? "" : product.getDescription());
        fields.put("categoryId", String.valueOf(product.getCategory().getId()));
        fields.put("active", String.valueOf(product.isActive()));
        fields.put("isFeatured", String.valueOf(product.isFeatured()));
        fields.put("isNew", "false");
        fields.put("sortOrder", "0");
        fields.put("isOnSale", "false");
        fields.put("sku", product.getSku());
        fields.put("price", product.getPrice().toPlainString());
        fields.put("stock", String.valueOf(product.getStock()));
        fields.put("manageStock", String.valueOf(product.isManageStock()));
        return fields;
    }

    /** Lo que envía el formulario de un producto con todo bloqueado: nada de lo que es del POS. */
    private static Map<String, String> webOnly(Map<String, String> fields) {
        for (String locked : new String[]{"name", "description", "sku", "price", "stock", "manageStock"}) {
            fields.remove(locked);
        }
        return fields;
    }

    private ResultActions update(Product product, Map<String, String> fields, MockMultipartFile... files)
            throws Exception {
        var request = multipart("/api/admin/products/" + product.getId());
        request.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        fields.forEach((key, value) -> request.param(key, value));
        return mockMvc.perform(request
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions ownership(Product product) throws Exception {
        return mockMvc.perform(get("/api/admin/products/" + product.getId() + "/pos-ownership")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token));
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("images", "foto.png", "image/png", pngBytes());
    }

    private static String variationJson(String sku, int price, int stock) {
        return "{\"attributeValueIdsByAttributeId\":{},\"price\":" + price + ",\"stock\":" + stock
                + ",\"sku\":\"" + sku + "\"}";
    }
}
