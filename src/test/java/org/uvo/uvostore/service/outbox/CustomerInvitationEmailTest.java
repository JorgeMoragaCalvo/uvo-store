package org.uvo.uvostore.service.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithCustomerAccounts;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F24. El correo de invitación: que se encole con la compra de un invitado y qué lleva dentro.
 *
 * <p>En este paquete porque {@code invitationBody(...)} es visible solo en el paquete — igual que
 * {@code MercadoPagoPreferenceTest} vive junto a {@code MercadoPagoServiceImpl} por lo mismo. Es la única
 * forma de acreditar lo que se envía: en los tests no hay SMTP.
 */
@WithCustomerAccounts
class CustomerInvitationEmailTest extends IntegrationTestSupport {

    @Autowired
    private OrderEmailComposer composer;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("La compra de un invitado encola su invitación junto al acuse de recibo")
    void aGuestPurchaseQueuesTheInvitation() throws Exception {
        Fixture f = guestOrder("inv-queue");

        assertThat(outboxRepository.findByOrderIdOrderByIdAsc(f.order().getId()))
                .extracting(OutboxMessage::getType)
                .containsExactly(OutboxType.ORDER_PLACED_EMAIL, OutboxType.CUSTOMER_INVITATION_EMAIL);
    }

    @Test
    @DisplayName("El enlace lleva el token, y el correo dice de qué pedido viene")
    void theBodyCarriesTheActivationLink() throws Exception {
        Fixture f = guestOrder("inv-body");

        String body = composer.invitationBody(f.customer(), f.order());

        // Sin el token el correo no sirve de nada: es lo único que prueba que quien activa la cuenta es
        // el dueño del buzón.
        assertThat(body).contains(f.customer().getInvitationToken());
        assertThat(body).contains("/cuenta/activar?token=");
        assertThat(body).contains(f.order().getOrderNumber());
        assertThat(body).contains("un solo uso");
        // PROD-04: y lleva al storefront de SU tienda, no a una URL de plataforma única.
        assertThat(body).contains("http://" + hostHeader(f.store()) + "/cuenta/activar?token=");
    }

    @Test
    @DisplayName("PROD-04: dos tiendas, dos enlaces — y la tienda sale del pedido, no del contexto")
    void eachStoreGetsItsOwnLink() throws Exception {
        // La tienda se toma de la orden a propósito: esto corre en el despachador del outbox, donde no hay
        // ni petición ni TenantContext de la tienda.
        Fixture first = guestOrder("inv-host-a");
        Fixture second = guestOrder("inv-host-b");

        String firstBody = composer.invitationBody(first.customer(), first.order());
        String secondBody = composer.invitationBody(second.customer(), second.order());

        assertThat(firstBody).contains("http://" + hostHeader(first.store()) + "/cuenta/activar");
        assertThat(secondBody).contains("http://" + hostHeader(second.store()) + "/cuenta/activar");
        assertThat(hostHeader(first.store())).isNotEqualTo(hostHeader(second.store()));
        assertThat(firstBody).doesNotContain(hostHeader(second.store()));
    }

    private record Fixture(Store store, Customer customer, Order order) {
    }

    private Fixture guestOrder(String prefix) throws Exception {
        Store store = createStore(prefix);
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        String email = prefix + "-" + nextSeq() + "@test.local";

        mockMvc.perform(post("/api/v1/checkout")
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
                                """.formatted(email, product.getId())))
                .andExpect(status().isOk());
        Customer guest = customerRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
        Order order = entityManager.createQuery(
                        "select o from Order o where o.customer.id = :id", Order.class)
                .setParameter("id", guest.getId())
                .setMaxResults(1)
                .getSingleResult();
        return new Fixture(store, guest, order);
    }
}
