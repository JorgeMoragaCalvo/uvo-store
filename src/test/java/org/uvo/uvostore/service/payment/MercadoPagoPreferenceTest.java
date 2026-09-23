// Mismo paquete que MercadoPagoServiceImpl, igual que PaymentServiceImplTest con PaymentServiceImpl:
// buildPreferenceRequest es visible en el paquete a propósito, y no se vuelve público solo por el test.
package org.uvo.uvostore.service.payment;

import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.service.order.OrderStatusService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F02. Lo que MercadoPago cobra tiene que ser {@code order.getTotal()} y nada más.
 *
 * <p>Antes armaba la preferencia con el desglose —una línea por ítem a precio de lista, otra de envío
 * y otra de IVA— y ese desglose no era el total: nunca restaba {@code discountAmount}, y con
 * {@code prices_include_tax=true} añadía el IVA sobre precios que ya lo llevaban dentro. Es el mismo
 * fallo que ya se había corregido en Stripe; MercadoPago era la única de las tres pasarelas que seguía
 * reconstruyendo el importe.
 *
 * <p>Lo que hace que un cobro de más aquí no se vea como un cobro de más: {@code markPaid} compara
 * exacto contra el total, así que el dinero se mueve y la orden se queda en PENDING, sin stock
 * descontado y excluida de la conciliación. El cliente paga más y su pedido no avanza.
 *
 * <p>Unitario a propósito, sin contexto de Spring ni llamadas a la API — calcado de
 * {@code PaymentServiceImplTest}, y con los mismos números que el caso de Stripe para que los dos
 * cobros se puedan comparar de un vistazo.
 */
class MercadoPagoPreferenceTest {

    private final MercadoPagoServiceImpl service = new MercadoPagoServiceImpl(
            mock(OrderRepository.class),
            mock(PaymentGatewayConfigRepository.class),
            mock(OrderStatusService.class),
            mock(MercadoPagoWebhookSignature.class),
            "http://localhost:5173", 5000, 20000);

    @Test
    @DisplayName("Con IVA incluido en el precio y un cupón, se cobra el total y no 11.934 pesos de más")
    void chargesTheOrderTotalWithATaxInclusivePriceAndACoupon() {
        // El mismo escenario que PaymentServiceImplTest para Stripe: subtotal 48.970 con IVA ya
        // dentro, cupón del 10 %, total 44.854,87. El desglose anterior armaba 48.970 + 7.819 =
        // 56.789, un 27 % de más: se comía el descuento y cobraba el IVA por segunda vez.
        Order order = orderWith(
                new BigDecimal("48970.00"),
                new BigDecimal("4115.13"),
                new BigDecimal("7818.74"),
                new BigDecimal("44854.87"));

        assertThat(chargedAmountOf(order)).isEqualTo(44855L);
    }

    @Test
    @DisplayName("Sin IVA en el precio y sin descuento, se cobra el total igualmente")
    void chargesTheOrderTotalWithTaxExclusivePricingAndNoDiscount() {
        Order order = orderWith(new BigDecimal("10000.00"), BigDecimal.ZERO,
                new BigDecimal("1900.00"), new BigDecimal("11900.00"));

        assertThat(chargedAmountOf(order)).isEqualTo(11900L);
    }

    @Test
    @DisplayName("Una sola línea: el desglose es justamente lo que cobraba otra cosa")
    void buildsASingleLineItemAndNotTheBreakdown() {
        // Con tres ítems, envío e IVA, el desglose anterior habría dado cinco líneas. Si alguien
        // vuelve a "mejorar" el detalle que ve el comprador, este test cae antes que el cobro.
        Order order = orderWith(new BigDecimal("30000.00"), new BigDecimal("3000.00"),
                new BigDecimal("5700.00"), new BigDecimal("34200.00"));
        order.setShippingCost(new BigDecimal("3990.00"));
        order.setItems(List.of(item(order, new BigDecimal("10000.00")),
                item(order, new BigDecimal("10000.00")),
                item(order, new BigDecimal("10000.00"))));

        PreferenceRequest request = service.buildPreferenceRequest(order, null, null, null, null);

        assertThat(request.getItems())
                .as("una línea por el total, no ítems + envío + IVA")
                .hasSize(1);
        assertThat(request.getItems().get(0).getTitle()).isEqualTo("Pedido " + order.getOrderNumber());
        assertThat(chargedAmountOf(order))
                .as("el envío ya está dentro del total; sumarlo aparte lo cobraría dos veces")
                .isEqualTo(34200L);
    }

    @Test
    @DisplayName("La referencia externa sigue siendo el número de orden")
    void keepsTheOrderNumberAsExternalReference() {
        // Es lo único que liga la preferencia con la orden: el webhook y la conciliación
        // (MercadoPagoServiceImpl.reconcile) buscan por external_reference.
        Order order = orderWith(new BigDecimal("10000.00"), BigDecimal.ZERO,
                new BigDecimal("1900.00"), new BigDecimal("11900.00"));

        assertThat(service.buildPreferenceRequest(order, null, null, null, null).getExternalReference())
                .isEqualTo(order.getOrderNumber());
    }

    private long chargedAmountOf(Order order) {
        PreferenceRequest request = service.buildPreferenceRequest(order, null, null, null, null);
        long charged = 0;
        for (PreferenceItemRequest item : request.getItems()) {
            charged += item.getUnitPrice().setScale(0, RoundingMode.HALF_UP).longValueExact() * item.getQuantity();
        }
        return charged;
    }

    private Order orderWith(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal total) {
        Order order = Order.builder()
                .id(1L)
                .orderNumber("ORD-TEST-1")
                .customerEmail("cliente@test.local")
                .subtotal(subtotal)
                .discountAmount(discountAmount)
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(taxAmount)
                .total(total)
                .build();
        order.setItems(List.of(item(order, subtotal)));
        return order;
    }

    // El precio de la línea sale del subtotal, no de una constante: así el desglose que se cobraba
    // antes es reproducible a partir de estos mismos datos y los importes del test cuadran entre sí.
    private OrderItem item(Order order, BigDecimal subtotal) {
        return OrderItem.builder()
                .order(order)
                .productName("Producto de prueba")
                .productSku("SKU-1")
                .quantity(2)
                .price(subtotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP))
                .subtotal(subtotal)
                .build();
    }
}
