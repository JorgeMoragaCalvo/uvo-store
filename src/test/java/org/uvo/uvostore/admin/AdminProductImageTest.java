package org.uvo.uvostore.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product photos on the edit path. Until now `updateProduct` silently dropped the uploaded files
 * even though the controller bound them, and there was no way at all to delete an image — so the
 * only way to fix a wrong photo was deleting the product and creating it again.
 *
 * <p>Kept apart from {@code AdminCatalogCrudTest} because these are the only catalog tests that
 * write real files: the surrounding transaction rolls the rows back, but bytes on disk survive it,
 * so anything uploaded here has to be cleaned up by hand.
 */
class AdminProductImageTest extends IntegrationTestSupport {

    private static final Path UPLOAD_DIR = Paths.get("uploads");

    private final List<Path> writtenFiles = new ArrayList<>();

    @AfterEach
    void deleteUploadedFiles() throws IOException {
        for (Path file : writtenFiles) {
            Files.deleteIfExists(file);
        }
        writtenFiles.clear();
    }

    @Test
    @DisplayName("Editar un producto sube una imagen nueva y la marca como destacada")
    void updatingAProductPersistsANewFeaturedImage() throws Exception {
        Fixture fixture = fixture("img-update");
        Product product = createProduct(fixture.store(), fixture.category(), "Producto", BigDecimal.TEN);

        JsonNode updated = updateWithImages(fixture, product.getId(), image("featuredImage", "portada.png"));

        assertThat(updated.get("images")).hasSize(1);
        JsonNode image = updated.get("images").get(0);
        assertThat(image.get("isFeatured").asBoolean()).isTrue();
        assertThat(updated.get("featuredImage").asText()).isEqualTo(image.get("url").asText());
        assertThat(fileFor(image)).exists();
    }

    @Test
    @DisplayName("Subir una destacada nueva desmarca la anterior")
    void uploadingANewFeaturedImageUnfeaturesThePreviousOne() throws Exception {
        Fixture fixture = fixture("img-replace");
        Product product = createProduct(fixture.store(), fixture.category(), "Producto", BigDecimal.TEN);

        updateWithImages(fixture, product.getId(), image("featuredImage", "primera.png"));
        JsonNode updated = updateWithImages(fixture, product.getId(), image("featuredImage", "segunda.png"));

        assertThat(updated.get("images")).hasSize(2);
        long featured = countFeatured(updated);
        assertThat(featured).as("solo una imagen puede quedar destacada").isEqualTo(1);
        // The newest one wins — it's the one the admin just chose.
        JsonNode last = updated.get("images").get(updated.get("images").size() - 1);
        assertThat(last.get("isFeatured").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Quitar una imagen la elimina de la ficha y borra el archivo del disco")
    void removingAnImageDeletesTheRowAndTheFile() throws Exception {
        Fixture fixture = fixture("img-remove");
        Product product = createProduct(fixture.store(), fixture.category(), "Producto", BigDecimal.TEN);

        JsonNode withImages = updateWithImages(fixture, product.getId(),
                image("featuredImage", "portada.png"), image("images", "galeria.png"));
        assertThat(withImages.get("images")).hasSize(2);

        JsonNode gallery = imageThatIsNotFeatured(withImages);
        Path galleryFile = fileFor(gallery);
        assertThat(galleryFile).exists();

        JsonNode afterRemoval = removeImage(fixture, product.getId(), gallery.get("id").asLong());

        assertThat(afterRemoval.get("images")).hasSize(1);
        assertThat(galleryFile).doesNotExist();
    }

    @Test
    @DisplayName("Quitar la destacada promueve otra, para que el producto no quede sin miniatura")
    void removingTheFeaturedImagePromotesAnother() throws Exception {
        Fixture fixture = fixture("img-promote");
        Product product = createProduct(fixture.store(), fixture.category(), "Producto", BigDecimal.TEN);

        JsonNode withImages = updateWithImages(fixture, product.getId(),
                image("featuredImage", "portada.png"), image("images", "galeria.png"));
        long featuredId = imageThatIsFeatured(withImages).get("id").asLong();

        JsonNode afterRemoval = removeImage(fixture, product.getId(), featuredId);

        assertThat(afterRemoval.get("images")).hasSize(1);
        assertThat(countFeatured(afterRemoval)).isEqualTo(1);
        assertThat(afterRemoval.get("featuredImage").isNull())
                .as("el listado de admin muestra featuredImage, no puede quedar vacío")
                .isFalse();
    }

    @Test
    @DisplayName("No se puede borrar una imagen de otra tienda")
    void removingAnImageOfAnotherStoreIsRejected() throws Exception {
        Fixture owner = fixture("img-owner");
        Product product = createProduct(owner.store(), owner.category(), "Producto", BigDecimal.TEN);
        JsonNode withImage = updateWithImages(owner, product.getId(), image("featuredImage", "portada.png"));
        long imageId = withImage.get("images").get(0).get("id").asLong();
        writtenFiles.add(fileFor(withImage.get("images").get(0)));

        Fixture intruder = fixture("img-intruder");
        mockMvc.perform(delete("/api/admin/products/" + product.getId() + "/images/" + imageId)
                        .header("Host", hostHeader(intruder.store()))
                        .header("Authorization", "Bearer " + intruder.token()))
                .andExpect(status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Fixture(Store store, Category category, String token) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        Category category = createCategory(store, "Cat");
        return new Fixture(store, category, loginAdmin(store, admin));
    }

    private MockMultipartFile image(String field, String filename) {
        return new MockMultipartFile(field, filename, "image/png", pngBytes());
    }

    /** Issues the multipart PUT the admin form sends, and returns the product it responds with. */
    private JsonNode updateWithImages(Fixture fixture, Long productId, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/admin/products/" + productId);
        request.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        String response = mockMvc.perform(request
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token())
                        .param("productType", "simple")
                        .param("name", "Producto con foto")
                        .param("categoryId", String.valueOf(fixture.category().getId()))
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "IMG-" + productId)
                        .param("price", "19990")
                        .param("stock", "5")
                        .param("manageStock", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode product = objectMapper.readTree(response);
        product.get("images").forEach(image -> writtenFiles.add(fileFor(image)));
        return product;
    }

    private JsonNode removeImage(Fixture fixture, Long productId, long imageId) throws Exception {
        String response = mockMvc.perform(delete("/api/admin/products/" + productId + "/images/" + imageId)
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** The DTO's url ("http://host/uploads/products/xxx.png") → the file on disk. */
    private Path fileFor(JsonNode image) {
        String url = image.get("url").asText();
        return UPLOAD_DIR.resolve(url.substring(url.indexOf("/uploads/") + "/uploads/".length()));
    }

    private long countFeatured(JsonNode product) {
        long featured = 0;
        for (JsonNode image : product.get("images")) {
            if (image.get("isFeatured").asBoolean()) {
                featured++;
            }
        }
        return featured;
    }

    private JsonNode imageThatIsFeatured(JsonNode product) {
        for (JsonNode image : product.get("images")) {
            if (image.get("isFeatured").asBoolean()) {
                return image;
            }
        }
        throw new AssertionError("No hay imagen destacada");
    }

    private JsonNode imageThatIsNotFeatured(JsonNode product) {
        for (JsonNode image : product.get("images")) {
            if (!image.get("isFeatured").asBoolean()) {
                return image;
            }
        }
        throw new AssertionError("Todas las imágenes están destacadas");
    }
}
