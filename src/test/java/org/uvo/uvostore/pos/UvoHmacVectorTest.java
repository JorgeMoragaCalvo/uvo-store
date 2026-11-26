package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.security.UvoHmac;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-08. La firma UVO-HMAC contra el vector del contrato ({@code docs/contrato-pos-ecommerce/ejemplos/firma-vector.json},
 * §3). Se lee el archivo del repositorio y no una copia: si el contrato cambia el vector, esta prueba lo nota.
 *
 * <p>El bloque {@code control_alfozapi} reproduce la firma de {@code FirmaIntegracionTest} de AlfozApi: una
 * implementación que no lo reproduce está mal antes de mirar lo demás.
 */
class UvoHmacVectorTest {

    private static final Path VECTOR = Path.of("docs", "contrato-pos-ecommerce", "ejemplos", "firma-vector.json");

    @Test
    @DisplayName("Reproduce la firma de control de AlfozApi")
    void reproducesTheAlfozApiControlSignature() throws Exception {
        JsonNode vector = vector();
        JsonNode control = vector.get("control_alfozapi");
        String canonical = UvoHmac.canonical(control.get("method").asString(), control.get("path").asString(),
                vector.get("timestamp").asLong(), vector.get("nonce").asString(), control.get("key_id").asString(),
                control.get("body").asString().getBytes(StandardCharsets.UTF_8));

        assertThat(UvoHmac.sign(vector.get("secret_base64").asString(), canonical))
                .isEqualTo(control.get("signature").asString());
    }

    @Test
    @DisplayName("Cada caso del contrato da su canónica, su hash y su firma")
    void reproducesEveryContractCase() throws Exception {
        JsonNode vector = vector();
        String secret = vector.get("secret_base64").asString();
        for (JsonNode test : vector.get("casos")) {
            byte[] body = test.get("body").asString().getBytes(StandardCharsets.UTF_8);
            String canonical = UvoHmac.canonical(test.get("method").asString(), test.get("path").asString(),
                    vector.get("timestamp").asLong(), vector.get("nonce").asString(), test.get("key_id").asString(), body);

            assertThat(UvoHmac.sha256Hex(body)).as(test.get("caso").asString()).isEqualTo(test.get("body_sha256").asString());
            assertThat(canonical).as(test.get("caso").asString()).isEqualTo(test.get("canonical").asString());
            assertThat(UvoHmac.sign(secret, canonical)).as(test.get("caso").asString())
                    .isEqualTo(test.get("signature").asString());
            assertThat(UvoHmac.verify(secret, canonical, test.get("signature").asString())).isTrue();
        }
    }

    @Test
    @DisplayName("Solo vale la forma exacta: ni mayúsculas, ni sin prefijo, ni con espacios")
    void onlyTheExactSignatureFormIsAccepted() throws Exception {
        JsonNode vector = vector();
        JsonNode first = vector.get("casos").get(0);
        String secret = vector.get("secret_base64").asString();
        String canonical = first.get("canonical").asString();
        String signature = first.get("signature").asString();

        assertThat(UvoHmac.verify(secret, canonical, signature.toUpperCase())).isFalse();
        assertThat(UvoHmac.verify(secret, canonical, signature.substring(3))).isFalse();
        assertThat(UvoHmac.verify(secret, canonical, " " + signature)).isFalse();
        assertThat(UvoHmac.verify(secret, canonical + "x", signature)).isFalse();
    }

    @Test
    @DisplayName("El nonce: base64url de 22 a 64 caracteres; uno nuevo cumple el formato")
    void nonceFormat() {
        assertThat(UvoHmac.isValidNonce("3q2-7wEXAMPLEnonce_base64url")).isTrue();
        assertThat(UvoHmac.isValidNonce("corto")).isFalse();
        assertThat(UvoHmac.isValidNonce("a".repeat(65))).isFalse();
        assertThat(UvoHmac.isValidNonce("con espacios y mas de veintidos")).isFalse();
        assertThat(UvoHmac.isValidNonce(UvoHmac.newNonce())).isTrue();
    }

    @Test
    @DisplayName("Un secreto sirve si es base64 de al menos 32 bytes")
    void secretRules() throws Exception {
        assertThat(UvoHmac.isUsableSecret(vector().get("secret_base64").asString())).isTrue();
        assertThat(UvoHmac.isUsableSecret("c2hvcnQ=")).isFalse();
        assertThat(UvoHmac.isUsableSecret("no es base64 !!")).isFalse();
    }

    private static JsonNode vector() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(VECTOR));
    }
}
