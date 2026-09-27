package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09. La ficha padre de un producto variable no se compra: se compra una de sus variaciones.
 *
 * <p>El fallo: nada exigía {@code ProductType.SIMPLE} para una línea sin {@code variationId}, así que
 * una petición directa a la API podía pedir el padre. Lo que salía de ahí:
 *
 * <ul>
 *   <li>Un pedido <b>sin talla ni color</b>, que el comerciante no puede cumplir.</li>
 *   <li>Cobrado al precio de la variación más barata —{@code recalculateParentAggregate} pone ahí el
 *       mínimo— o a <b>cero</b> mientras el padre no tenga ninguna variación.</li>
 *   <li>Y en <b>cualquier cantidad</b>: el padre arrastra {@code manageStock = false} de por vida, así
 *       que la comprobación de stock se salta entera y el inventario nunca se mueve.</li>
 * </ul>
 *
 * <p>Se prueba por la API y no por la UI porque la UI sí guía a elegir variante; el agujero era la
 * petición directa.
 */
class VariableProductPurchaseTest extends IntegrationTestSupport {

    @Autowired
    private ProductVariationRepository variationRepository;
    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("El carrito rechaza la ficha padre y pide elegir variante")
    void theCartRefusesTheVariableParent() throws Exception {
        Store store = createStore("variable-cart");
        Product parent = variableParentWithVariation(store, BigDecimal.valueOf(19990), 5);

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {"items":[{"id":%d,"type":"product","quantity":1}]}
                                """.formatted(parent.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['items.0']").value("Elige una variante de este producto"));
    }

    @Test
    @DisplayName("El checkout tampoco la acepta, y no deja ninguna orden detrás")
    void theCheckoutRefusesItAndCreatesNoOrder() throws Exception {
        Store store = createStore("variable-checkout");
        disableShipping(store);
        Product parent = variableParentWithVariation(store, BigDecimal.valueOf(19990), 5);
        long ordersBefore = orderRepository.count();

        mockMvc.perform(checkout(store, parent.getId(), 1))
                .andExpect(status().isConflict());

        assertThat(orderRepository.count())
                .as("una orden sin variante no se puede cumplir: no debe existir")
                .isEqualTo(ordersBefore);
    }

    @Test
    @DisplayName("Ni el cascarón sin variaciones, que es el que valdría $0")
    void theEmptyShellCannotBeBoughtEither() throws Exception {
        // El escenario del titular del hallazgo: createVariableProduct deja el padre en price=0 y
        // recalculateParentAggregate no ha corrido porque todavía no hay ninguna variación.
        Store store = createStore("variable-shell");
        disableShipping(store);
        Product shell = variableParent(store, BigDecimal.ZERO, 0);

        mockMvc.perform(checkout(store, shell.getId(), 1))
                .andExpect(status().isConflict());

        assertThat(orderRepository.findAll().stream()
                .filter(o -> o.getStore().getId().equals(store.getId())))
                .isEmpty();
    }

    @Test
    @DisplayName("Y no se puede pedir cualquier cantidad aprovechando que el padre no controla stock")
    void anUnlimitedQuantityIsRefused() throws Exception {
        // manageStock=false en el padre hacía que la comprobación de stock se saltara entera: 500
        // unidades de algo con 5 pasaban sin más, y el inventario no se movía.
        Store store = createStore("variable-oversell");
        disableShipping(store);
        Product parent = variableParentWithVariation(store, BigDecimal.valueOf(19990), 5);

        mockMvc.perform(checkout(store, parent.getId(), 500))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Comprar la variación sí funciona, y cobra su precio")
    void buyingTheVariationWorks() throws Exception {
        // El control positivo: lo que se cierra es la ficha padre, no el producto variable.
        Store store = createStore("variable-ok");
        disableShipping(store);
        setSetting(store, "tax_rate", "0");
        Product parent = variableParentWithVariation(store, BigDecimal.valueOf(19990), 5);
        ProductVariation variation = variationRepository.findByProductId(parent.getId()).get(0);

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {
                                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                                  "region": "RM", "commune": "Santiago",
                                  "items": [{"id":%d,"type":"variation","quantity":1}],
                                  "paymentMethod": "manual"
                                }
                                """.formatted(variation.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(19990));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Product variableParentWithVariation(Store store, BigDecimal price, int stock) {
        // Se monta como lo deja el código real: el padre con el mínimo de sus variaciones como precio y
        // la suma de sus stocks (recalculateParentAggregate), pero manageStock en false para siempre.
        Product parent = variableParent(store, price, stock);
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(parent);
        variation.setSku("VAR-" + nextSeq());
        variation.setPrice(price);
        variation.setStock(stock);
        variation.setActive(true);
        variationRepository.save(variation);
        return parent;
    }

    private Product variableParent(Store store, BigDecimal price, int stock) {
        Product parent = createProduct(store, createCategory(store, "Ropa"), "Polera variable", price);
        parent.setProductType(ProductType.VARIABLE);
        parent.setStock(stock);
        parent.setManageStock(false);
        return productRepository.save(parent);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Long productId, int quantity) {
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {
                          "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                          "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                          "region": "RM", "commune": "Santiago",
                          "items": [{"id":%d,"type":"product","quantity":%d}],
                          "paymentMethod": "manual"
                        }
                        """.formatted(productId, quantity));
    }
}
