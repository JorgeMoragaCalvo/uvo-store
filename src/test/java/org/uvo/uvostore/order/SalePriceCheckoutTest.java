package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F11. El precio de oferta tiene que llegar hasta el dinero, no quedarse en la base.
 *
 * <p>El admin guardaba `isOnSale` y `salePrice` y nadie los leía: el catálogo mostraba el precio de
 * lista, el carrito cotizaba el de lista y el `OrderItem` cobraba el de lista. Una oferta era un campo
 * de solo escritura, así que el día que algo la pusiera —la API de admin ya la acepta— el producto
 * aparecería bajo "Ofertas" cobrado a precio completo.
 *
 * <p>Se comprueba de punta a punta porque el valor está en que los cuatro sitios digan lo mismo: lo que
 * muestra la ficha es lo que cobra el checkout.
 */
class SalePriceCheckoutTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("La ficha del producto muestra la oferta y deja el precio normal para tachar")
    void theProductDetailShowsTheSalePrice() throws Exception {
        Store store = createStore("sale-detail");
        Product product = productOnSale(store, "10000", "7990", null, null);

        mockMvc.perform(get("/api/v1/products/" + product.getSlug())
                        .header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(7990))
                // Mismo convenio que ProductVariationDto, que el storefront ya pinta barrado.
                .andExpect(jsonPath("$.compareAtPrice").value(10000))
                .andExpect(jsonPath("$.formattedPrice").value("$7.990"));
    }

    @Test
    @DisplayName("Y el checkout cobra la oferta, no el precio de lista")
    void theCheckoutChargesTheSalePrice() throws Exception {
        Store store = createStore("sale-checkout");
        disableShipping(store);
        setSetting(store, "tax_rate", "0");
        Product product = productOnSale(store, "10000", "7990", null, null);

        Order order = checkout(store, product);

        assertThat(order.getItems()).singleElement().satisfies(item ->
                assertThat(item.getPrice())
                        .as("el snapshot de la línea es lo que se cobra y lo que va al documento del POS")
                        .isEqualByComparingTo("7990"));
        assertThat(order.getTotal()).isEqualByComparingTo("7990");
    }

    @Test
    @DisplayName("Una oferta caducada se cobra al precio normal")
    void anExpiredSaleChargesTheRegularPrice() throws Exception {
        Store store = createStore("sale-expired");
        disableShipping(store);
        setSetting(store, "tax_rate", "0");
        Product product = productOnSale(store, "10000", "7990", null,
                Instant.now().minus(1, ChronoUnit.DAYS));

        Order order = checkout(store, product);

        assertThat(order.getTotal()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Una oferta más cara que el precio normal no encarece la compra")
    void aBrokenSaleNeverRaisesThePrice() throws Exception {
        // Un error de configuración no puede cobrarle al cliente por encima del precio del producto.
        Store store = createStore("sale-broken");
        disableShipping(store);
        setSetting(store, "tax_rate", "0");
        Product product = productOnSale(store, "10000", "12000", null, null);

        Order order = checkout(store, product);

        assertThat(order.getTotal()).isEqualByComparingTo("10000");
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Product productOnSale(Store store, String price, String salePrice, Instant startsAt, Instant endsAt) {
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto en oferta",
                new BigDecimal(price));
        product.setOnSale(true);
        product.setSalePrice(new BigDecimal(salePrice));
        product.setSaleStartsAt(startsAt);
        product.setSaleEndsAt(endsAt);
        return productRepository.save(product);
    }

    private Order checkout(Store store, Product product) throws Exception {
        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {
                                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                                  "region": "RM", "commune": "Santiago",
                                  "items": [{"id":%d,"type":"product","quantity":1}],
                                  "paymentMethod": "manual"
                                }
                                """.formatted(product.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
    }
}
