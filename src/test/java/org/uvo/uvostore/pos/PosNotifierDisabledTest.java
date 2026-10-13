package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-12. Con la integración apagada no se notifica ninguna orden, y eso significa que no se emite
 * ningún documento tributario.
 *
 * <p>Unitario por lo mismo que {@code PosOrderNotifierTest}: {@code notifyOrder} es
 * {@code REQUIRES_NEW}, así que bajo la transacción con rollback de {@code IntegrationTestSupport}
 * abriría una transacción nueva que no vería las fixtures.
 *
 * <p><b>El corte se comprueba en el notificador y no en el listener</b> porque es donde está: el
 * notificador es el único cuello de botella de salida, así que cubre también a
 * {@code PosNotificationRetryJob}, que lo llama directamente con su propia bandera.
 *
 * <p>Los dos casos son las dos mitades de la política, y la segunda es la que hasta ahora no existía:
 * {@code pos_sync_enabled} llevaba desde el principio en el panel sin que lo leyera nadie.
 */
class PosNotifierDisabledTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductSyncMappingRepository mappingRepository = mock(ProductSyncMappingRepository.class);
    private final PosConnectionRepository connectionRepository = mock(PosConnectionRepository.class);
    private final PosClient posClient = mock(PosClient.class);

    @Test
    @DisplayName("Con el interruptor de plataforma apagado no se toca el POS")
    void nothingIsNotifiedWhenThePlatformDisablesIt() {
        Order order = orderWithASyncedItem();

        boolean settled = notifierWith(PosPolicyFixture.platformDisabled()).notifyOrder(order.getId());

        // true: no queda nada pendiente. Un false metería la orden en la cola del reintento, que es el
        // sentido opuesto de tener la integración apagada.
        assertThat(settled).isTrue();
        // verifyNoInteractions y no verify(never()).sendOrder(...): notifyOrder envuelve todo en un
        // try/catch que registra y avisa a Sentry, así que un fallo al armar el payload se vería igual
        // que "no se notificó". Esto afirma que no se llegó siquiera a mirar el cliente.
        verifyNoInteractions(posClient);
        assertThat(order.isPosSynced()).isFalse();
        assertThat(order.getSyncAttempts()).isZero();
    }

    @Test
    @DisplayName("Con la plataforma habilitada pero la tienda sin sincronización, tampoco")
    void nothingIsNotifiedWhenTheStoreDisablesIt() {
        Order order = orderWithASyncedItem();

        boolean settled = notifierWith(PosPolicyFixture.storeDisabled()).notifyOrder(order.getId());

        assertThat(settled).isTrue();
        verifyNoInteractions(posClient);
        verifyNoInteractions(mappingRepository);
    }

    private PosOrderNotifier notifierWith(PosIntegrationPolicy policy) {
        return new PosOrderNotifier(orderRepository, mappingRepository, connectionRepository, posClient,
                new PosOrderPayloadMapper(), policy, 3);
    }

    /**
     * Una orden que <b>sí</b> tendría que notificarse: su producto está mapeado a una empresa del POS.
     * Si el corte no estuviera, esto llegaría al cliente — que es lo que hace que la mordida muerda.
     */
    private Order orderWithASyncedItem() {
        Store store = new Store();
        store.setId(1L);
        store.setName("Tienda");
        store.setSlug("tienda");

        Product product = new Product();
        product.setId(10L);
        product.setName("Producto");
        product.setPrice(BigDecimal.valueOf(10000));

        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(10000));

        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-PROD12");
        order.setStore(store);
        order.setItems(new ArrayList<>(java.util.List.of(item)));
        order.setPosSynced(false);
        order.setSyncAttempts(0);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setCompanyId(7L);
        when(mappingRepository.findByProductId(10L)).thenReturn(Optional.of(mapping));
        return order;
    }
}
