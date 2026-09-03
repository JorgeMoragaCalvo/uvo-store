package org.uvo.uvostore.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.config.RequiredSecret;
import org.uvo.uvostore.entity.payment.EncryptionKeyHolder;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// C4: the three configuration secrets lost their committed defaults, so each consumer has to fail
// the startup — loudly and by name — instead of quietly running on a secret anyone can read in the
// repo's history. Plain JUnit: these are constructor guards, no Spring context needed.
class SecretValidationTest {

    private static final String VALID_JWT_SECRET = "una-clave-de-prueba-suficientemente-larga-32b";
    private static final String VALID_ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static SecretKey originalKey;

    // EncryptionKeyHolder publishes its key through a static field (the bridge that
    // EncryptedCredentialsConverter needs, see its javadoc), and surefire runs every test class in
    // one JVM with Spring contexts cached across them. Constructing throwaway holders here would
    // otherwise leave a toy key behind and break decryption for whichever @SpringBootTest runs next.
    @BeforeAll
    static void captureKey() {
        try {
            originalKey = EncryptionKeyHolder.key();
        } catch (IllegalStateException notInitializedYet) {
            originalKey = null;
        }
    }

    @AfterAll
    static void restoreKey() throws Exception {
        Field instance = EncryptionKeyHolder.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, originalKey);
    }

    @Test
    @DisplayName("JwtService: secreto ausente falla nombrando JWT_SECRET")
    void jwtSecretMissing() {
        assertThatThrownBy(() -> new JwtService("", 86400000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("JwtService: secreto demasiado corto falla antes de la WeakKeyException de jjwt")
    void jwtSecretTooShort() {
        assertThatThrownBy(() -> new JwtService("corto", 86400000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("JwtService: el valor por defecto commiteado es rechazado")
    void jwtSecretLegacyDefault() {
        assertThatThrownBy(() -> new JwtService(RequiredSecret.LEGACY_JWT_SECRET, 86400000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("JwtService: un secreto válido construye y firma")
    void jwtSecretValid() {
        JwtService service = new JwtService(VALID_JWT_SECRET, 86400000L);
        String token = service.generateToken(1L, "admin@uvostore.cl", "ADMIN", 1L, java.util.List.of(), 0);
        assertThat(service.parseClaims(token).getSubject()).isEqualTo("admin@uvostore.cl");
    }

    @Test
    @DisplayName("PlatformApiKeyAuthFilter: clave ausente falla el arranque, no la petición")
    void platformKeyMissing() {
        assertThatThrownBy(() -> new PlatformApiKeyAuthFilter(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_API_KEY");
    }

    @Test
    @DisplayName("PlatformApiKeyAuthFilter: el valor por defecto commiteado es rechazado")
    void platformKeyLegacyDefault() {
        assertThatThrownBy(() -> new PlatformApiKeyAuthFilter(RequiredSecret.LEGACY_PLATFORM_API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_API_KEY");
    }

    @Test
    @DisplayName("PlatformApiKeyAuthFilter: una clave cualquiera no vacía construye")
    void platformKeyValid() {
        assertThatCode(() -> new PlatformApiKeyAuthFilter("una-clave-de-plataforma")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EncryptionKeyHolder: clave ausente falla nombrando APP_ENCRYPTION_KEY")
    void encryptionKeyMissing() {
        assertThatThrownBy(() -> new EncryptionKeyHolder(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("EncryptionKeyHolder: base64 inválido da IllegalStateException, no el IllegalArgumentException crudo")
    void encryptionKeyNotBase64() {
        assertThatThrownBy(() -> new EncryptionKeyHolder("no-es-base64-válido!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    @DisplayName("EncryptionKeyHolder: una clave de 16 bytes no sirve para AES-256")
    void encryptionKeyWrongLength() {
        assertThatThrownBy(() -> new EncryptionKeyHolder(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    // Deliberately NOT a failure: rotating this key makes every already-encrypted
    // PaymentGatewayConfig.credentials row unreadable, so EncryptionKeyHolder only warns. See its
    // constructor for the reasoning.
    @Test
    @DisplayName("EncryptionKeyHolder: el valor por defecto commiteado arranca (solo advierte)")
    void encryptionKeyLegacyDefaultOnlyWarns() {
        assertThatCode(() -> new EncryptionKeyHolder(RequiredSecret.LEGACY_ENCRYPTION_KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EncryptionKeyHolder: una clave válida queda disponible en el puente estático")
    void encryptionKeyValid() {
        new EncryptionKeyHolder(VALID_ENCRYPTION_KEY);
        assertThat(EncryptionKeyHolder.key().getEncoded()).hasSize(32);
    }
}
