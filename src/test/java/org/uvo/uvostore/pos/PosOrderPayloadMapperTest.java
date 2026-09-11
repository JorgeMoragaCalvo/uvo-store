package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.service.pos.PosOrderItem;
import org.uvo.uvostore.service.pos.PosOrderPayload;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3.1 y G3.3, test de contrato. El cuerpo que se le manda a UvoPOS llevaba solo
 * {@code source}, {@code order_number} e ítems con {@code product_id}/{@code quantity}/{@code price}:
 * sin total, envío, descuento ni IVA, un documento tributario emitido a partir de él sale por un
 * monto distinto del cobrado; y sin clave de idempotencia, el reintento de G2 lo emite dos veces.
 *
 * <p>El test va sobre el mapper y no sobre {@code PosClient} a propósito: el {@code RestClient} de
 * PosClient se instancia dentro de la clase y no hay dónde interceptarlo, mientras que el mapper es
 * una función pura — y es donde vive todo lo que depende del contrato. Fija el cuerpo COMPLETO: si
 * alguien cambia o quita un nombre de campo, esto falla, que es justo lo que se quiere mientras la
 * especificación de la plataforma siga sin confirmarse.
 */
class PosOrderPayloadMapperTest {

    private final PosOrderPayloadMapper mapper = new PosOrderPayloadMapper();

    @Test
    @DisplayName("El cuerpo lleva los importes de la orden, los del ítem y la clave de idempotencia")
    void theBodyFixesTheWholeContract() {
        Order order = order("ORD-2026-0001", "100000", "10000", "3990", "17100", "111090");

        PosOrderPayload payload = mapper.toPayload(order, 42L, List.of(
                new PosOrderItem(777L, 2, new BigDecimal("30000"), new BigDecimal("60000"), new BigDecimal("11400")),
                new PosOrderItem(888L, 1, new BigDecimal("40000"), new BigDecimal("40000"), new BigDecimal("7600"))
        ));

        assertThat(payload.body()).isEqualTo(Map.of(
                "source", "uvostore",
                "order_number", "ORD-2026-0001",
                "idempotency_key", "ORD-2026-0001:42",
                "subtotal", new BigDecimal("100000"),
                "discount_amount", new BigDecimal("10000"),
                "shipping_cost", new BigDecimal("3990"),
                "tax_amount", new BigDecimal("17100"),
                "total", new BigDecimal("111090"),
                "items", List.of(
                        Map.of("product_id", 777L, "quantity", 2, "price", new BigDecimal("30000"),
                                "subtotal", new BigDecimal("60000"), "tax_amount", new BigDecimal("11400")),
                        Map.of("product_id", 888L, "quantity", 1, "price", new BigDecimal("40000"),
                                "subtotal", new BigDecimal("40000"), "tax_amount", new BigDecimal("7600"))
                )
        ));
    }

    @Test
    @DisplayName("La clave de idempotencia es la misma en dos llamadas para la misma orden y empresa")
    void theIdempotencyKeyIsStable() {
        Order order = order("ORD-2026-0002", "1000", "0", "0", "190", "1190");

        String first = mapper.toPayload(order, 7L, List.of(item())).idempotencyKey();
        String second = mapper.toPayload(order, 7L, List.of(item())).idempotencyKey();

        // Esto —y no otra cosa— es lo que impide que un reintento emita un segundo documento
        // tributario. Una clave aleatoria o derivada del reloj rompe este test, que es el punto.
        assertThat(first).isEqualTo(second).isEqualTo("ORD-2026-0002:7");
    }

    @Test
    @DisplayName("Cada empresa tiene su propia clave: son dos documentos distintos")
    void theIdempotencyKeyIsPerCompany() {
        Order order = order("ORD-2026-0003", "1000", "0", "0", "190", "1190");

        assertThat(mapper.toPayload(order, 1L, List.of(item())).idempotencyKey())
                .isNotEqualTo(mapper.toPayload(order, 2L, List.of(item())).idempotencyKey());
    }

    @Test
    @DisplayName("La clave del cuerpo y la de la cabecera son la misma")
    void theKeyInTheBodyIsTheOneSentAsAHeader() {
        Order order = order("ORD-2026-0004", "1000", "0", "0", "190", "1190");

        PosOrderPayload payload = mapper.toPayload(order, 9L, List.of(item()));

        assertThat(payload.body().get("idempotency_key")).isEqualTo(payload.idempotencyKey());
    }

    @Test
    @DisplayName("Un importe ausente viaja como cero, no como null")
    void missingAmountsTravelAsZero() {
        Order order = new Order();
        order.setOrderNumber("ORD-2026-0005");
        order.setSubtotal(new BigDecimal("1000"));
        order.setTotal(new BigDecimal("1000"));
        order.setDiscountAmount(null);
        order.setShippingCost(null);
        order.setTaxAmount(null);

        Map<String, Object> body = mapper.toPayload(order, 1L,
                List.of(new PosOrderItem(1L, 1, new BigDecimal("1000"), null, null))).body();

        assertThat(body.get("discount_amount")).isEqualTo(BigDecimal.ZERO);
        assertThat(body.get("shipping_cost")).isEqualTo(BigDecimal.ZERO);
        assertThat(body.get("tax_amount")).isEqualTo(BigDecimal.ZERO);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem = ((List<Map<String, Object>>) body.get("items")).getFirst();
        assertThat(firstItem.get("subtotal")).isEqualTo(BigDecimal.ZERO);
        assertThat(firstItem.get("tax_amount")).isEqualTo(BigDecimal.ZERO);
    }

    private static PosOrderItem item() {
        return new PosOrderItem(1L, 1, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("190"));
    }

    private static Order order(String orderNumber, String subtotal, String discount, String shipping, String tax, String total) {
        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setSubtotal(new BigDecimal(subtotal));
        order.setDiscountAmount(new BigDecimal(discount));
        order.setShippingCost(new BigDecimal(shipping));
        order.setTaxAmount(new BigDecimal(tax));
        order.setTotal(new BigDecimal(total));
        return order;
    }
}
