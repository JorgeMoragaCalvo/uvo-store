package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POS-4 e IMP-08. Lo que el POS dice tener de un lote de sus Productos ({@code POST /api/v1/products/snapshot},
 * contrato §5), leído por {@code PosClient.readCatalog} para la conciliación ({@code PosReconciliationService}).
 *
 * <p>{@code data[]} trae, por Producto: {@code product_id}, {@code sku}, {@code version}, {@code name}, {@code price},
 * {@code tax_exempt} y {@code stock_web}. Una variante de la tienda es un Producto del POS más, con su propia fila.
 * {@code missing[]} trae los ids que el POS no tiene o que no son de esa empresa.
 *
 * <p>La lectura es tolerante, fila a fila: un campo que falta o no se entiende queda en {@code null}, y eso solo apaga
 * lo que dependía de él (sin {@code stock_web} no se concilia el stock; sin {@code version}, el catálogo). Una fila
 * sin {@code product_id} no se puede asignar a nada y se ignora.
 */
public record PosCatalogSnapshot(Map<Long, Row> rows, Set<Long> missing, String failure) {

    public record Row(Long productId, String sku, Long version, Integer stockWeb, BigDecimal price, String name,
                      Boolean taxExempt) {
    }

    public static PosCatalogSnapshot failed(String reason) {
        return new PosCatalogSnapshot(Map.of(), Set.of(), reason);
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
            Object exempt = row.get("tax_exempt");
            rows.put(productId, new Row(productId, asText(row.get("sku")), asLong(row.get("version")),
                    asInt(row.get("stock_web")), asDecimal(row.get("price")), asText(row.get("name")),
                    exempt instanceof Boolean flag ? flag : null));
        }
        Set<Long> missing = new HashSet<>();
        if (body.get("missing") instanceof List<?> ids) {
            for (Object id : ids) {
                Long value = asLong(id);
                if (value != null) {
                    missing.add(value);
                }
            }
        }
        return new PosCatalogSnapshot(Map.copyOf(rows), Set.copyOf(missing), null);
    }

    /**
     * §5: cada id pedido aparece en {@code data} o en {@code missing}. Los que no aparecen en ninguno: un lote
     * incompleto, que no se concilia como si el POS no los tuviera.
     */
    public Set<Long> unaccounted(java.util.Collection<Long> requested) {
        Set<Long> absent = new HashSet<>();
        for (Long id : requested) {
            if (!rows.containsKey(id) && !missing.contains(id)) {
                absent.add(id);
            }
        }
        return absent;
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
