package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.order.Order;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G3.1 y G3.3. Arma el cuerpo que se le manda a UvoPOS al completarse una orden.
 *
 * <p>Hasta aquí el cuerpo se construía dentro de {@code PosClient.notifyOrder} y llevaba solo
 * {@code source}, {@code order_number} e {@code items} con {@code product_id}, {@code quantity} y
 * {@code price}. Faltaban los importes de la orden —total, envío, descuento, IVA—, así que un
 * documento tributario emitido a partir de esto salía por un monto distinto del cobrado; y faltaba
 * cualquier forma de idempotencia, sin la cual un reintento (G2) duplica el documento.
 *
 * <h2>ATENCIÓN: el contrato con la plataforma SII NO está confirmado</h2>
 * Los nombres de campo y la convención de idempotencia de abajo son una apuesta razonable, no un
 * acuerdo. Todo lo que depende de ese contrato vive en esta clase a propósito: alinearlo cuando
 * llegue la especificación es editar un archivo, y su test de contrato dirá exactamente qué cambió.
 * Queda por confirmar:
 * <ol>
 *   <li>Los nombres: {@code subtotal}, {@code discount_amount}, {@code shipping_cost},
 *       {@code tax_amount}, {@code total} — y los mismos {@code subtotal}/{@code tax_amount} por
 *       ítem.</li>
 *   <li>Si los importes van como número decimal (lo que se hace aquí) o en unidades enteras de la
 *       moneda. En CLP no hay decimales, así que la diferencia no se nota hasta que aparezca una
 *       moneda que sí los tenga.</li>
 *   <li>Si el descuento va como monto positivo a restar (lo que se hace aquí) o negativo.</li>
 *   <li>Cuál de las dos convenciones de idempotencia usa la plataforma: campo en el cuerpo
 *       ({@code idempotency_key}) o cabecera {@code Idempotency-Key}. Se mandan las dos por eso
 *       mismo — mandar de más es inocuo, quedarse corto duplica documentos.</li>
 *   <li>Si la clave debe ser única por orden o por orden y empresa. Aquí es por orden y empresa,
 *       porque una orden con productos de dos empresas genera dos documentos distintos y una clave
 *       compartida haría que el segundo se descartara como repetido.</li>
 * </ol>
 */
@Component
public class PosOrderPayloadMapper {

    private static final String SOURCE = "uvostore";

    public PosOrderPayload toPayload(Order order, Long companyId, List<PosOrderItem> items) {
        String idempotencyKey = idempotencyKey(order.getOrderNumber(), companyId);

        // LinkedHashMap y no Map.of(): el orden estable hace legible el test de contrato, y Map.of
        // además no admite nulos ni pasa de diez pares.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", SOURCE);
        body.put("order_number", order.getOrderNumber());
        body.put("idempotency_key", idempotencyKey);
        body.put("subtotal", amount(order.getSubtotal()));
        body.put("discount_amount", amount(order.getDiscountAmount()));
        body.put("shipping_cost", amount(order.getShippingCost()));
        body.put("tax_amount", amount(order.getTaxAmount()));
        body.put("total", amount(order.getTotal()));
        body.put("items", items.stream().map(PosOrderPayloadMapper::itemBody).toList());

        return new PosOrderPayload(idempotencyKey, Collections.unmodifiableMap(body));
    }

    /**
     * Estable por orden y empresa: dos llamadas para la misma orden y la misma empresa producen la
     * misma clave. Eso —y no otra cosa— es lo que impide que un reintento emita un segundo
     * documento tributario, así que no puede llevar nada aleatorio ni derivado del reloj.
     */
    public static String idempotencyKey(String orderNumber, Long companyId) {
        return orderNumber + ":" + companyId;
    }

    private static Map<String, Object> itemBody(PosOrderItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", item.productId());
        body.put("quantity", item.quantity());
        body.put("price", amount(item.price()));
        body.put("subtotal", amount(item.subtotal()));
        body.put("tax_amount", amount(item.taxAmount()));
        return Collections.unmodifiableMap(body);
    }

    // Un importe ausente se manda como 0 y no como null: la plataforma tendría que interpretar el
    // null, y "sin IVA" y "IVA cero" son lo mismo para el documento.
    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
