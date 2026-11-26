package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.order.Order;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El cuerpo de {@code POST /api/v1/orders/external}: la venta web que se registra en UvoPOS. Sigue el §7 del contrato
 * UvoPOS ↔ uvo-store v1 ({@code docs/contrato-pos-ecommerce.md}), y sus ejemplos
 * ({@code docs/contrato-pos-ecommerce/ejemplos/venta-*.request.json}) lo fijan en {@code PosOrderPayloadMapperTest}.
 *
 * <ul>
 *   <li>Todo en snake_case y todo obligatorio. Los importes son enteros CLP.</li>
 *   <li>{@code idempotency_key} = {@code {order_number}:{company_id}} (§8), igual a la cabecera {@code Idempotency-Key}.
 *       Una orden es de una sola empresa (GAP-06, {@link PosSaleCoverage}), así que los totales son los de la orden.</li>
 *   <li>Cada línea lleva el {@code product_id} del Producto POS vendido. En una variante es el Producto POS de esa
 *       variante (§4): el contrato no tiene {@code variant_id}.</li>
 *   <li>GAP-05: con {@code tax_included = true} el subtotal y el descuento van con IVA y el IVA está contenido; con
 *       {@code false} el IVA se suma. Las líneas suman el descuento y el IVA de la orden. El envío va solo en
 *       {@code shipping_cost}, fuera de la base del IVA.</li>
 * </ul>
 */
@Component
public class PosOrderPayloadMapper {

    private static final String SOURCE = "uvostore";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public PosOrderPayload toPayload(Order order, Long companyId, List<PosOrderItem> items) {
        String idempotencyKey = idempotencyKey(order.getOrderNumber(), companyId);

        // LinkedHashMap y no Map.of(): el orden estable es el de los ejemplos del contrato, y Map.of además no admite
        // nulos ni pasa de diez pares.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("order_number", order.getOrderNumber());
        body.put("idempotency_key", idempotencyKey);
        body.put("subtotal", amount(order.getSubtotal()));
        body.put("discount_amount", amount(order.getDiscountAmount()));
        body.put("shipping_cost", amount(order.getShippingCost()));
        body.put("tax_amount", amount(order.getTaxAmount()));
        body.put("tax_included", order.isTaxIncluded());
        body.put("total", amount(order.getTotal()));
        body.put("items", items.stream().map(PosOrderPayloadMapper::itemBody).toList());

        return new PosOrderPayload(idempotencyKey, Collections.unmodifiableMap(body));
    }

    /**
     * Estable por orden y empresa: dos llamadas para la misma orden y la misma empresa producen la misma clave. Eso
     * —y no otra cosa— es lo que impide que un reintento registre una segunda venta, así que no puede llevar nada
     * aleatorio ni derivado del reloj.
     */
    public static String idempotencyKey(String orderNumber, Long companyId) {
        return orderNumber + ":" + companyId;
    }

    /** Los bytes que se fijan en la fila del outbox y se firman. Compacto y con el orden de {@link #toPayload}. */
    public String toJson(PosOrderPayload payload) {
        return JSON.writeValueAsString(payload.body());
    }

    /**
     * Las invariantes del §7 que el POS verifica, comprobadas antes de mandar nada: si fallan, el POS respondería
     * {@code 422 VALIDATION} con la misma clave para siempre. Devuelve el primer problema, o vacío si cuadra.
     */
    public static Optional<String> invariantViolation(Map<String, Object> body) {
        List<?> items = (List<?>) body.get("items");
        long subtotal = 0;
        long discount = 0;
        long tax = 0;
        for (Object entry : items) {
            Map<?, ?> item = (Map<?, ?>) entry;
            long quantity = ((Number) item.get("quantity")).longValue();
            long price = ((Number) item.get("price")).longValue();
            long lineSubtotal = ((Number) item.get("subtotal")).longValue();
            long lineDiscount = ((Number) item.get("discount_amount")).longValue();
            if (quantity <= 0) {
                return Optional.of("una línea tiene cantidad " + quantity);
            }
            if (lineSubtotal != quantity * price) {
                return Optional.of("el subtotal de la línea del producto " + item.get("product_id")
                        + " no es cantidad × precio");
            }
            if (lineDiscount < 0) {
                return Optional.of("una línea tiene un descuento negativo");
            }
            subtotal += lineSubtotal;
            discount += lineDiscount;
            tax += ((Number) item.get("tax_amount")).longValue();
        }
        long orderSubtotal = ((Number) body.get("subtotal")).longValue();
        long orderDiscount = ((Number) body.get("discount_amount")).longValue();
        long orderShipping = ((Number) body.get("shipping_cost")).longValue();
        long orderTax = ((Number) body.get("tax_amount")).longValue();
        long orderTotal = ((Number) body.get("total")).longValue();
        if (subtotal != orderSubtotal) {
            return Optional.of("las líneas suman un subtotal de " + subtotal + " y la orden dice " + orderSubtotal);
        }
        if (discount != orderDiscount) {
            return Optional.of("las líneas suman un descuento de " + discount + " y la orden dice " + orderDiscount);
        }
        if (tax != orderTax) {
            return Optional.of("las líneas suman un IVA de " + tax + " y la orden dice " + orderTax);
        }
        if (orderShipping < 0) {
            return Optional.of("el envío es negativo");
        }
        long expected = orderSubtotal - orderDiscount + orderShipping
                + (Boolean.TRUE.equals(body.get("tax_included")) ? 0 : orderTax);
        if (expected != orderTotal) {
            return Optional.of("el total debería ser " + expected + " y la orden dice " + orderTotal);
        }
        return Optional.empty();
    }

    private static Map<String, Object> itemBody(PosOrderItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", item.productId());
        body.put("quantity", item.quantity());
        body.put("price", amount(item.price()));
        body.put("subtotal", amount(item.subtotal()));
        body.put("discount_amount", amount(item.discountAmount()));
        body.put("tax_amount", amount(item.taxAmount()));
        return Collections.unmodifiableMap(body);
    }

    // Enteros CLP (§7). Un importe ausente se manda como 0: "sin IVA" y "IVA cero" son lo mismo para el documento.
    private static long amount(BigDecimal value) {
        return value == null ? 0L : value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
