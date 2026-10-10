package org.uvo.uvostore.service.order.event;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F24. El cuerpo del correo de invitación.
 *
 * <p>En este paquete y no junto a {@code CustomerInvitationTest} porque {@code body(...)} es visible solo
 * en el paquete — igual que {@code MercadoPagoPreferenceTest} vive junto a {@code MercadoPagoServiceImpl}
 * por lo mismo. Es la única forma de acreditar lo que se envía: en los tests no hay SMTP y
 * {@code EmailServiceImpl} registra y omite el envío.
 *
 * <p><b>No se intenta observar el envío de extremo a extremo.</b> El oyente es {@code AFTER_COMMIT} y bajo
 * la transacción de {@code IntegrationTestSupport} no llega a correr; invocarlo a mano abriría su
 * {@code REQUIRES_NEW}, que escaparía al rollback del test y dejaría basura en la base. Lo que sí se
 * comprueba, en {@code AsyncListenerDispatchTest}, es que salga por el executor del correo.
 */
class CustomerInvitationEmailListenerTest extends IntegrationTestSupport {

    @Autowired
    private CustomerInvitationEmailListener listener;
    @Autowired
    private CustomerRepository customerRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("El enlace lleva el token, y el correo dice de qué pedido viene")
    void theBodyCarriesTheActivationLink() throws Exception {
        Store store = createStore("inv-body");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        String email = "cuerpo-" + nextSeq() + "@test.local";

        mockMvc.perform(checkout(store, product, email)).andExpect(status().isOk());
        Customer guest = customerRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
        Order order = entityManager.createQuery(
                        "select o from Order o where o.customer.id = :id", Order.class)
                .setParameter("id", guest.getId())
                .setMaxResults(1)
                .getSingleResult();

        String body = listener.body(guest, order);

        // Sin el token el correo no sirve de nada: es lo único que prueba que quien activa la cuenta es
        // el dueño del buzón.
        assertThat(body).contains(guest.getInvitationToken());
        assertThat(body).contains("/cuenta/activar?token=");
        assertThat(body).contains(order.getOrderNumber());
        assertThat(body).contains("un solo uso");
        // PROD-04: y lleva al storefront de SU tienda, no a una URL de plataforma única.
        assertThat(body).contains("http://" + hostHeader(store) + "/cuenta/activar?token=");
    }

    @Test
    @DisplayName("PROD-04: dos tiendas, dos enlaces — y la tienda sale del pedido, no del contexto")
    void eachStoreGetsItsOwnLink() throws Exception {
        // Antes los dos cuerpos eran idénticos salvo el token, porque el host venía de app.frontend-url.
        // Y la tienda se toma de la orden a propósito: este oyente corre en un hilo del pool de correo
        // después del commit, donde no hay ni petición ni TenantContext.
        Fixture first = guestOrder("inv-host-a");
        Fixture second = guestOrder("inv-host-b");

        String firstBody = listener.body(first.customer, first.order);
        String secondBody = listener.body(second.customer, second.order);

        assertThat(firstBody).contains("http://" + hostHeader(first.store) + "/cuenta/activar");
        assertThat(secondBody).contains("http://" + hostHeader(second.store) + "/cuenta/activar");
        assertThat(hostHeader(first.store)).isNotEqualTo(hostHeader(second.store));
        assertThat(firstBody).doesNotContain(hostHeader(second.store));
    }

    private record Fixture(Store store, Customer customer, Order order) {
    }

    private Fixture guestOrder(String prefix) throws Exception {
        Store store = createStore(prefix);
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        String email = prefix + "-" + nextSeq() + "@test.local";

        mockMvc.perform(checkout(store, product, email)).andExpect(status().isOk());
        Customer guest = customerRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
        Order order = entityManager.createQuery(
                        "select o from Order o where o.customer.id = :id", Order.class)
                .setParameter("id", guest.getId())
                .setMaxResults(1)
                .getSingleResult();
        return new Fixture(store, guest, order);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Product product, String email) {
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {
                          "customer": {"email":"%s","firstName":"Invitado","lastName":"Comprador","phone":"+56911111111"},
                          "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                          "region": "RM", "commune": "Santiago",
                          "items": [{"id":%d,"type":"product","quantity":1}],
                          "paymentMethod": "manual"
                        }
                        """.formatted(email, product.getId()));
    }
}
