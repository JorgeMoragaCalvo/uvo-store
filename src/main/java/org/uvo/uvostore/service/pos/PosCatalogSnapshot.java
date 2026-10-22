package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POS-4. Lo que el POS dice tener de un lote de productos, leído por {@code PosClient.readCatalog} para la
 * conciliación ({@code PosReconciliationService}).
 *
 * <p><b>Contrato propuesto, no confirmado</b> (POS-1, como {@code PosOrderPayloadMapper}): el POS todavía no
 * ofrece esta lectura. Se le pide {@code POST /api/v1/products/snapshot} con {@code {"product_ids": [...]}} y se
 * espera {@code {"data": [{"product_id", "version", "stock_web", "price", "name", "description"}]}}. Con todos los
 * campos del POS y no solo algunos, por la misma razón que {@code product.updated} (POS-3): la conciliación avanza
 * la versión, y un estado parcial perdería cambios.
 *
 * <p>La lectura es tolerante, fila a fila: un campo que falta o no se entiende queda en {@code null}, y eso solo
 * apaga lo que dependía de él (sin {@code stock_web} no se concilia el stock; sin {@code version}, el catálogo).
 * Una fila sin {@code product_id} no se puede asignar a nada y se ignora.
 *
 * <p>POS-5. La fila de un producto con variantes trae además {@code variants}, con
 * {@code [{"variant_id", "sku", "price", "stock_web", "attributes"}]}: el estado completo de sus variantes. Se lee
 * con la misma tolerancia ({@link PosVariants#fromJson}), y la lista se valida antes de usarla.
 */
public record PosCatalogSnapshot(Map<Long, Row> rows, String failure) {

    /** {@code variants} es {@code null} si la fila no las trae; el {@code stock} de cada una es su {@code stock_web}. */
    public record Row(Long productId, Long version, Integer stockWeb, BigDecimal price, String name,
                      String description, List<PosVariantFields> variants) {

        public Row(Long productId, Long version, Integer stockWeb, BigDecimal price, String name,
                   String description) {
            this(productId, version, stockWeb, price, name, description, null);
        }
    }

    public static PosCatalogSnapshot failed(String reason) {
        return new PosCatalogSnapshot(Map.of(), reason);
    }

    public boolean isFailed() {
        return failure != null;
    }

    /** El cuerpo de una respuesta 2xx. Sin una lista {@code data}, la lectura no sirve y es un fallo. */
    public static PosCatalogSnapshot parse(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof List<?> data)) {
            return failed("El POS respondió sin la lista \"data\"");
        }
        Map<Long, Row> rows = new HashMap<>();
        for (Object entry : data) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }
            Long productId = asLong(row.get("product_id"));
            if (productId == null) {
                continue;
            }
            rows.put(productId, new Row(productId, asLong(row.get("version")), asInt(row.get("stock_web")),
                    asDecimal(row.get("price")), asText(row.get("name")), asText(row.get("description")),
                    PosVariants.fromJson(row.get("variants"), "variant_id", "stock_web")));
        }
        return new PosCatalogSnapshot(Map.copyOf(rows), null);
    }

    // Un 7 puede llegar como 7, 7.0 o "7", según cómo serialice el POS. Lo que no es un entero se descarta.
    static Long asLong(Object value) {
        BigDecimal decimal = asDecimal(value);
        if (decimal == null) {
            return null;
        }
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    static Integer asInt(Object value) {
        Long number = asLong(value);
        return number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? null : number.intValue();
    }

    static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
