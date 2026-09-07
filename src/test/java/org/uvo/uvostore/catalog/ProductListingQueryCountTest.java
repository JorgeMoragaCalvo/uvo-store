package org.uvo.uvostore.catalog;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7. El listado de productos cargaba la página y luego disparaba un SELECT por producto para sus
 * imágenes, otro para sus variaciones y uno por variación para los atributos: 1 + N + N + N×V
 * consultas por página. No había ni un @EntityGraph, ni un JOIN FETCH, ni un @BatchSize en todo el
 * proyecto.
 *
 * <p>La aserción no fija un número de consultas —eso se rompería con cualquier cambio inocente en el
 * mapeo— sino la propiedad que importa: el costo de una página **no crece con la cantidad de filas**.
 * Dos tiendas, una con 2 productos y otra con 6, mismo contenido por producto: si @BatchSize
 * desaparece, la segunda cuesta ~16 consultas más que la primera y el test cae.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductListingQueryCountTest extends IntegrationTestSupport {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Una página cuesta lo mismo con 2 productos que con 6")
    void aPageCostsTheSameNoMatterHowManyRowsItHas() throws Exception {
        long few = queriesToListAStoreWith(2);
        long many = queriesToListAStoreWith(6);

        assertThat(many)
                .as("con @BatchSize el listado no paga por fila; sin él, 4 productos más cuestan ~16 consultas más")
                .isEqualTo(few);
    }

    private long queriesToListAStoreWith(int productCount) throws Exception {
        Store store = createStore("nplus1-" + productCount);
        Category category = createCategory(store, "Cat");
        User admin = createAdmin(store, "nplus1-" + productCount);
        String token = loginAdmin(store, admin);

        for (int i = 0; i < productCount; i++) {
            Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
            addImage(store, product);
            addImage(store, product);
            addVariation(store, product);
            addVariation(store, product);
            productRepository.save(product);
        }

        // Sin esto, las entidades recién creadas siguen en el contexto de persistencia y el listado
        // no consultaría nada: mediríamos la caché de primer nivel, no la base de datos.
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();

        mockMvc.perform(get("/api/admin/products?perPage=50")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        return statistics.getPrepareStatementCount() - before;
    }

    private void addImage(Store store, Product product) {
        ProductImage image = new ProductImage();
        image.setStore(store);
        image.setProduct(product);
        image.setImagePath("productos/" + nextSeq() + ".png");
        product.getProductImages().add(image);
    }

    private void addVariation(Store store, Product product) {
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku("VAR-" + nextSeq());
        variation.setPrice(BigDecimal.valueOf(1200));
        variation.setStock(5);
        product.getVariations().add(variation);
    }
}
