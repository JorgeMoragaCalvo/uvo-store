package org.uvo.uvostore.entity.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.RequiredSecret;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

// Static bridge for EncryptedCredentialsConverter (a plain JPA AttributeConverter, not a Spring
// bean — see its javadoc) to reach a Spring-configured key. APP_ENCRYPTION_KEY must be a
// base64-encoded 256-bit (32-byte) value; generate one with `openssl rand -base64 32`.
@Component
public class EncryptionKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyHolder.class);

    private static volatile SecretKey instance;

    public EncryptionKeyHolder(@Value("${app.encryption-key:}") String base64Key) {
        RequiredSecret.require(base64Key, "APP_ENCRYPTION_KEY");

        // The one secret that only warns about its legacy default instead of refusing to boot:
        // rotating this key makes every already-encrypted PaymentGatewayConfig.credentials row
        // unreadable, so failing here would brick an existing environment rather than protect it.
        // Rotation with re-encryption is its own task; production must start from a fresh key.
        if (RequiredSecret.LEGACY_ENCRYPTION_KEY.equals(base64Key)) {
            log.warn("APP_ENCRYPTION_KEY es el valor por defecto que estuvo commiteado en application.properties. " +
                     "Está en el historial de git y es público: aceptable en local, NUNCA en producción. " +
                     "Genera una nueva con: {} (invalida las credenciales de pasarela ya cifradas).",
                     RequiredSecret.GENERATE_HINT);
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY no es base64 válido. Genera una con: " + RequiredSecret.GENERATE_HINT, e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY debe decodificar a 32 bytes (AES-256), tiene " + keyBytes.length);
        }
        instance = new SecretKeySpec(keyBytes, "AES");
    }

    public static SecretKey key() {
        if (instance == null) {
            throw new IllegalStateException("EncryptionKeyHolder no ha sido inicializado todavía por Spring");
        }
        return instance;
    }
}
