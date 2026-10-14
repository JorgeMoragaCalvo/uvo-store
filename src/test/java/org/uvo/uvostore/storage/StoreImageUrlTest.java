package org.uvo.uvostore.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-05b. La URL de una imagen sale de la <b>tienda</b>, no de por dónde llegó la petición.
 *
 * <p>Antes {@code LocalFileStorageService.publicUrl} la armaba con {@code getScheme()},
 * {@code getServerName()} y {@code getServerPort()}. Detrás de un proxy que termina TLS eso describe el
 * tramo interno: {@code http} y el puerto del upstream, así que desde una página https el navegador
 * recibía imágenes en {@code http://…:8080} —contenido mixto, bloqueado—. Aquí se reproduce ese tramo
 * con un {@code Host} que lleva un puerto que no es el público: si la URL siguiera saliendo de la
 * petición, el puerto aparecería en ella.
 *
 * <p>{@code MockHttpServletRequest} deriva {@code getServerName()} y {@code getServerPort()} de la
 * cabecera {@code Host}, que es justo lo que hace que este test distinga las dos implementaciones.
 */
class StoreImageUrlTest extends IntegrationTestSupport {

    @Test
    @DisplayName("El puerto con el que llegó la petición no entra en la URL de la imagen")
    void theRequestsPortDoesNotLeakIntoTheImageUrl() throws Exception {
        Store store = createStore("img-port");
        Category category = withImage(createCategory(store, "Con imagen"), "categories/" + store.getId() + "/foto.png");

        // Se entra por <slug>.localhost:9999, como llegaría desde el tramo interno de un proxy. La plantilla
        // pública de los tests (app.public-url.api) no lleva puerto, y esa es la que debe mandar.
        mockMvc.perform(get("/api/v1/categories/" + category.getSlug())
                        .header("Host", hostHeader(store) + ":9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image").value("http://" + hostHeader(store) + "/uploads/categories/"
                        + store.getId() + "/foto.png"));
    }

    @Test
    @DisplayName("Con dominio propio verificado la imagen sale de ese dominio, aunque se entre por el subdominio")
    void aVerifiedCustomDomainIsUsedForTheImageEvenWhenEnteringBySubdomain() throws Exception {
        Store store = createStore("img-dom");
        String domain = "img-" + nextSeq() + ".example";
        store.setDomain(domain);
        store.setDomainVerifiedAt(Instant.now());
        storeRepository.save(store);
        Category category = withImage(createCategory(store, "Con imagen"), "categories/" + store.getId() + "/foto.png");

        mockMvc.perform(get("/api/v1/categories/" + category.getSlug())
                        .header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image").value("http://" + domain + "/uploads/categories/"
                        + store.getId() + "/foto.png"));
    }

    private Category withImage(Category category, String imagePath) {
        category.setImage(imagePath);
        return categoryRepository.save(category);
    }
}
