package org.uvo.uvostore.service.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** PROD-18. Lo que puede y lo que no puede terminar en {@code audit_events.details}. */
class AuditDiffTest {

    @Test
    @DisplayName("Un monto con otra escala no es un cambio: la base devuelve 10000.00 y el formulario manda 10000")
    void amountsAreComparedByValue() {
        AuditDiff diff = AuditDiff.create()
                .value("price", new BigDecimal("10000.00"), new BigDecimal("10000"))
                .value("count", 3, 3L);

        assertThat(diff.hasChanges()).isFalse();
        assertThat(diff.toMap()).isNull();
    }

    @Test
    @DisplayName("Un cambio guarda antes y después, con los montos sin ceros de más")
    void aChangeKeepsFromAndTo() {
        Map<String, Object> details = AuditDiff.create()
                .value("tax_rate", "19", "21")
                .value("price", new BigDecimal("10000.00"), new BigDecimal("12990"))
                .toMap();

        assertThat(details).containsEntry("changes", Map.of(
                "tax_rate", Map.of("from", "19", "to", "21"),
                "price", Map.of("from", "10000", "to", "12990")));
    }

    @Test
    @DisplayName("Un secreto nunca deja su valor: solo si se puso, se reemplazó o se borró")
    void secretsNeverKeepTheirValue() {
        Map<String, Object> details = AuditDiff.create()
                .secret("puesto", null, "sk_live_PUESTO")
                .secret("reemplazado", "sk_live_VIEJO", "sk_live_NUEVO")
                .secret("borrado", "sk_live_BORRADO", "  ")
                .secret("igual", "sk_live_IGUAL", "sk_live_IGUAL")
                .secretSubmitted("enviado", true, true)
                .secretSubmitted("noEnviado", true, false)
                .toMap();

        assertThat(details.toString()).doesNotContain("sk_live");
        assertThat(details).containsEntry("changes", Map.of(
                "puesto", "set", "reemplazado", "replaced", "borrado", "cleared", "enviado", "replaced"));
    }

    @Test
    @DisplayName("De un mapa de credenciales, solo los nombres que parecen un campo; el resto se cuenta sin nombrarlo")
    void credentialKeysAreFilteredAndValuesDropped() {
        Map<String, String> before = new HashMap<>(Map.of("accessToken", "APP_USR-VIEJO", "publicKey", "PUB-IGUAL"));
        Map<String, String> after = new HashMap<>(before);
        after.put("accessToken", "APP_USR-NUEVO");
        after.put("webhookSecret", "whsec-NUEVO");
        // Una clave elegida por el cliente puede ser cualquier cosa, también un secreto pegado en el campo equivocado.
        after.put("APP_USR-pegado-como-clave", "x");

        Map<String, Object> details = AuditDiff.create().credentials("credentials", before, after).toMap();

        assertThat(details.toString()).doesNotContain("APP_USR", "whsec", "PUB-IGUAL");
        assertThat(details).containsEntry("changes", Map.of("credentials", Map.of(
                "accessToken", "replaced", "webhookSecret", "set", AuditDiff.OTHER_KEYS, 1)));
    }

    @Test
    @DisplayName("El texto libre solo deja el nombre del campo, y el contexto no cuenta como cambio")
    void opaqueFieldsAndContext() {
        AuditDiff onlyContext = AuditDiff.create().with("code", "CUPON10").opaque("notes", "igual", "igual");
        assertThat(onlyContext.hasChanges()).isFalse();
        assertThat(onlyContext.toMap()).isEqualTo(Map.of("code", "CUPON10"));

        Map<String, Object> details = AuditDiff.create().opaque("notes", "nota vieja privada", "nota nueva privada").toMap();
        assertThat(details).isEqualTo(Map.of("changedFields", List.of("notes")));
    }

    @Test
    @DisplayName("Un conjunto deja lo que entró y lo que salió, ordenado")
    void membersKeepAddedAndRemoved() {
        Map<String, Object> details = AuditDiff.create()
                .members("roles", Set.of("Bodega", "Ventas"), Set.of("Ventas", "Administrador", "Caja"))
                .toMap();

        assertThat(details).containsEntry("changes", Map.of("roles",
                Map.of("added", List.of("Administrador", "Caja"), "removed", List.of("Bodega"))));
    }

    @Test
    @DisplayName("Un texto largo se corta: nunca entra un cuerpo completo; pero un cambio después del corte sí cuenta")
    void longTextIsTruncatedButStillCompared() {
        String base = "x".repeat(200);
        AuditDiff diff = AuditDiff.create().value("name", base + "a", base + "b");

        assertThat(diff.hasChanges()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> change = (Map<String, Object>) ((Map<String, Object>) diff.toMap().get("changes")).get("name");
        assertThat((String) change.get("to")).hasSize(AuditDiff.MAX_TEXT);
    }
}
