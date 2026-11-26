package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.service.pos.PosOrderItem;
import org.uvo.uvostore.service.pos.PosOrderPayload;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3, GAP-05 e IMP-08, test de contrato. El cuerpo de la venta (§7) contra los cuatro ejemplos del contrato
 * ({@code docs/contrato-pos-ecommerce/ejemplos/venta-*.request.json}), leídos del repositorio: una orden armada con
 * los importes del ejemplo tiene que dar exactamente ese JSON. Y los bytes del ejemplo simple son los del vector de
 * firma, así que lo que la tienda firma es lo que el contrato fija.
 */
class PosOrderPayloadMapperTest {

    private static final Path EXAMPLES = Path.of("docs", "contrato-pos-ecommerce", "ejemplos");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PosOrderPayloadMapper mapper = new PosOrderPayloadMapper();

    @ParameterizedTest
    @ValueSource(strings = {"venta-simple", "venta-variante", "venta-cupon-envio", "venta-iva-no-incluido"})
    @DisplayName("Cada ejemplo del contrato sale tal cual, y cumple las invariantes del §7")
    void eachContractExampleIsReproduced(String name) throws Exception {
        JsonNode expected = JSON.readTree(Files.readString(EXAMPLES.resolve(name + ".request.json")));

        PosOrderPayload payload = fromExample(expected);
        JsonNode actual = JSON.readTree(mapper.toJson(payload));

        assertThat(actual).isEqualTo(expected);
        assertThat(PosOrderPayloadMapper.invariantViolation(payload.body())).isEmpty();
        assertThat(payload.idempotencyKey()).isEqualTo(expected.get("idempotency_key").asString());
    }

    @Test
    @DisplayName("Los bytes del ejemplo simple son los del vector de firma: compactos y en el orden del contrato")
    void theSimpleSaleBytesAreTheSignatureVectorBytes() throws Exception {
        JsonNode vector = JSON.readTree(Files.readString(EXAMPLES.resolve("firma-vector.json")));
        JsonNode sale = vector.get("casos").get(0);

        String json = mapper.toJson(fromExample(JSON.readTree(Files.readString(EXAMPLES.resolve("venta-simple.request.json")))));

        assertThat(json).isEqualTo(sale.get("body").asString());
        assertThat(UvoHmac.sha256Hex(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(sale.get("body_sha256").asString());
    }

    @Test
    @DisplayName("Los importes viajan como enteros CLP, y un importe ausente como cero")
    void amountsAreIntegerPesos() {
        Order order = order("ORD-2026-0005", "1000.00", null, null, null, "1000");

        Map<String, Object> body = mapper.toPayload(order, 1L,
                List.of(new PosOrderItem(1L, 1, new BigDecimal("1000.00"), new BigDecimal("1000.00"), null, null))).body();

        assertThat(body.get("subtotal")).isEqualTo(1000L);
        assertThat(body.get("discount_amount")).isEqualTo(0L);
        assertThat(body.get("shipping_cost")).isEqualTo(0L);
        assertThat(body.get("tax_amount")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) body.get("items")).getFirst();
        assertThat(item).doesNotContainKey("variant_id").containsEntry("price", 1000L).containsEntry("tax_amount", 0L);
    }

    @Test
    @DisplayName("La clave es {order_number}:{company_id}: estable, por empresa, y la misma del cuerpo")
    void theIdempotencyKey() {
        Order order = order("ORD-2026-0002", "1000", "0", "0", "190", "1190");

        String first = mapper.toPayload(order, 7L, List.of(item())).idempotencyKey();
        String second = mapper.toPayload(order, 7L, List.of(item())).idempotencyKey();
        PosOrderPayload otherCompany = mapper.toPayload(order, 8L, List.of(item()));

        assertThat(first).isEqualTo(second).isEqualTo("ORD-2026-0002:7");
        assertThat(otherCompany.idempotencyKey()).isNotEqualTo(first);
        assertThat(otherCompany.body().get("idempotency_key")).isEqualTo(otherCompany.idempotencyKey());
    }

    @Test
    @DisplayName("Una orden que no cuadra se detecta antes de enviarla: el POS la rechazaría para siempre")
    void anOrderThatDoesNotAddUpIsCaught() {
        // Las líneas suman 1000 de subtotal y la orden dice 1100.
        Order wrongSubtotal = order("ORD-X-1", "1100", "0", "0", "190", "1290");
        // total = subtotal - descuento + envío + IVA (sin IVA incluido) = 1190, y dice 1200.
        Order wrongTotal = order("ORD-X-2", "1000", "0", "0", "190", "1200");

        assertThat(PosOrderPayloadMapper.invariantViolation(mapper.toPayload(wrongSubtotal, 1L, List.of(item())).body()))
                .hasValueSatisfying(message -> assertThat(message).contains("subtotal"));
        assertThat(PosOrderPayloadMapper.invariantViolation(mapper.toPayload(wrongTotal, 1L, List.of(item())).body()))
                .hasValueSatisfying(message -> assertThat(message).contains("total"));
        assertThat(PosOrderPayloadMapper.invariantViolation(
                mapper.toPayload(order("ORD-X-3", "1000", "0", "0", "190", "1190"), 1L, List.of(item())).body()))
                .isEmpty();
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /** La orden y las líneas que darían el ejemplo: lo que la tienda tiene guardado de esa venta. */
    private PosOrderPayload fromExample(JsonNode example) {
        Order order = order(example.get("order_number").asString(), example.get("subtotal").asString(),
                example.get("discount_amount").asString(), example.get("shipping_cost").asString(),
                example.get("tax_amount").asString(), example.get("total").asString());
        order.setTaxIncluded(example.get("tax_included").asBoolean());
        List<PosOrderItem> items = new ArrayList<>();
        for (JsonNode line : example.get("items")) {
            items.add(new PosOrderItem(line.get("product_id").asLong(), line.get("quantity").asInt(),
                    new BigDecimal(line.get("price").asString()), new BigDecimal(line.get("subtotal").asString()),
                    new BigDecimal(line.get("discount_amount").asString()),
                    new BigDecimal(line.get("tax_amount").asString())));
        }
        String key = example.get("idempotency_key").asString();
        Long company = Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
        return mapper.toPayload(order, company, items);
    }

    private static PosOrderItem item() {
        return new PosOrderItem(1L, 1, new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("190"));
    }

    private static Order order(String orderNumber, String subtotal, String discount, String shipping, String tax,
                               String total) {
        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setSubtotal(new BigDecimal(subtotal));
        order.setDiscountAmount(discount == null ? null : new BigDecimal(discount));
        order.setShippingCost(shipping == null ? null : new BigDecimal(shipping));
        order.setTaxAmount(tax == null ? null : new BigDecimal(tax));
        order.setTotal(new BigDecimal(total));
        return order;
    }
}
