package org.uvo.uvostore.service.audit;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * PROD-18. La única forma de armar el {@code details} de una fila de auditoría. Existe para que "nunca secretos ni
 * cuerpos completos" no dependa de que cada servicio se acuerde:
 *
 * <ul>
 *   <li>{@link #value} guarda antes y después, y solo si cambió. Es para campos que no son secretos;</li>
 *   <li>{@link #secret}, {@link #secretSubmitted} y {@link #credentials} guardan solo el nombre de la clave y si se
 *       puso, se reemplazó o se borró. Nunca el valor;</li>
 *   <li>{@link #opaque} guarda solo el nombre de un campo de texto libre que cambió (notas, descripción, teléfono);</li>
 *   <li>{@link #with} agrega contexto (qué pasarela, qué código), que no cuenta como cambio;</li>
 *   <li>todo texto se corta a {@value #MAX_TEXT} caracteres.</li>
 * </ul>
 *
 * <p>Las ediciones graban solo si {@link #hasChanges()}: el panel reenvía el formulario completo en cada guardado, y
 * sin esto cada guardado sin cambios dejaría una fila.
 */
public final class AuditDiff {

    static final int MAX_TEXT = 120;

    // Las claves de credenciales las elige el cliente (el mapa se fusiona sin lista de claves permitidas), así que solo
    // se guardan las que parecen un nombre de campo. Las seis reales cumplen: accessToken, publicKey, webhookSecret,
    // childCommerceCode, subscriptionKey, originCountyCode. El resto se cuenta, sin nombrarlo.
    private static final Pattern KEY_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    static final String OTHER_KEYS = "_otherKeys";

    private final Map<String, Object> context = new LinkedHashMap<>();
    private final Map<String, Object> changes = new LinkedHashMap<>();
    private final List<String> changedFields = new ArrayList<>();

    public static AuditDiff create() {
        return new AuditDiff();
    }

    /** Contexto: identifica de qué se habla, no es un cambio. */
    public AuditDiff with(String key, Object value) {
        context.put(key, display(value));
        return this;
    }

    /** Antes y después de un campo que no es secreto, si cambió. Los montos se comparan por valor: 10000 = 10000.00. */
    public AuditDiff value(String field, Object from, Object to) {
        if (!Objects.equals(comparable(from), comparable(to))) {
            Map<String, Object> fromTo = new LinkedHashMap<>();
            fromTo.put("from", display(from));
            fromTo.put("to", display(to));
            changes.put(field, fromTo);
        }
        return this;
    }

    /** Contexto en bloque: la foto de lo que se crea o se borra. Solo campos que no son secretos. */
    public AuditDiff withAll(Map<String, ?> fields) {
        fields.forEach(this::with);
        return this;
    }

    /** {@link #value} para cada campo de dos fotos de la misma entidad (antes y después de editarla). */
    public AuditDiff values(Map<String, ?> before, Map<String, ?> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.forEach(key -> value(key, before.get(key), after.get(key)));
        return this;
    }

    /** Solo el nombre de un campo de texto libre que cambió: su contenido puede ser cualquier cosa. */
    public AuditDiff opaque(String field, Object from, Object to) {
        if (!Objects.equals(comparable(from), comparable(to))) {
            changedFields.add(field);
        }
        return this;
    }

    /** Un secreto cuyo valor anterior y nuevo se conocen en claro: se guarda {@code set}, {@code replaced} o {@code cleared}. */
    public AuditDiff secret(String field, String from, String to) {
        String state = secretState(from, to);
        if (state != null) {
            changes.put(field, state);
        }
        return this;
    }

    /**
     * Un secreto que se reemplaza sin comparar: cuando lo guardado está cifrado y descifrarlo podría fallar (una clave
     * rotada), justo en el guardado que lo arregla. El panel nunca rellena los secretos, así que mandar uno es
     * siempre a propósito.
     */
    public AuditDiff secretSubmitted(String field, boolean wasSet, boolean submitted) {
        if (submitted) {
            changes.put(field, wasSet ? "replaced" : "set");
        }
        return this;
    }

    /** Un mapa de credenciales: por clave, solo si se puso, se reemplazó o se borró. */
    public AuditDiff credentials(String field, Map<String, String> before, Map<String, String> after) {
        Map<String, String> safeBefore = before == null ? Map.of() : before;
        Map<String, String> safeAfter = after == null ? Map.of() : after;
        Set<String> keys = new TreeSet<>(safeBefore.keySet());
        keys.addAll(safeAfter.keySet());
        Map<String, Object> states = new LinkedHashMap<>();
        int otherKeys = 0;
        for (String key : keys) {
            String state = secretState(safeBefore.get(key), safeAfter.get(key));
            if (state == null) {
                continue;
            }
            if (KEY_NAME.matcher(key).matches()) {
                states.put(key, state);
            } else {
                otherKeys++;
            }
        }
        if (otherKeys > 0) {
            states.put(OTHER_KEYS, otherKeys);
        }
        if (!states.isEmpty()) {
            changes.put(field, states);
        }
        return this;
    }

    /** Un conjunto (roles, permisos): lo que entró y lo que salió. */
    public AuditDiff members(String field, Collection<String> before, Collection<String> after) {
        Set<String> added = new TreeSet<>(after == null ? Set.of() : after);
        Set<String> removed = new TreeSet<>(before == null ? Set.of() : before);
        if (before != null) {
            added.removeAll(before);
        }
        if (after != null) {
            removed.removeAll(after);
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("added", added.stream().map(AuditDiff::display).toList());
            delta.put("removed", removed.stream().map(AuditDiff::display).toList());
            changes.put(field, delta);
        }
        return this;
    }

    public boolean hasChanges() {
        return !changes.isEmpty() || !changedFields.isEmpty();
    }

    /** Lo que se guarda en {@code details}; nulo si no hay nada. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(context);
        if (!changes.isEmpty()) {
            map.put("changes", new LinkedHashMap<>(changes));
        }
        if (!changedFields.isEmpty()) {
            map.put("changedFields", List.copyOf(changedFields));
        }
        return map.isEmpty() ? null : map;
    }

    private static String secretState(String from, String to) {
        String before = blankToNull(from);
        String after = blankToNull(to);
        if (Objects.equals(before, after)) {
            return null;
        }
        if (before == null) {
            return "set";
        }
        return after == null ? "cleared" : "replaced";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // Para comparar, sin cortar: un cambio después del carácter 120 sigue siendo un cambio.
    private static Object comparable(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    // Para guardar.
    private static Object display(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(AuditDiff::display).toList();
        }
        Object comparable = comparable(value);
        if (comparable instanceof Long) {
            return comparable;
        }
        String text = comparable.toString();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
}
