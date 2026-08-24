package org.uvo.uvostore.entity.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

// Static bridge for EncryptedCredentialsConverter (a plain JPA AttributeConverter, not a Spring
// bean — see its javadoc) to reach a Spring-configured key. APP_ENCRYPTION_KEY must be a
// base64-encoded 256-bit (32-byte) value; generate one with `openssl rand -base64 32`.
@Component
public class EncryptionKeyHolder {

    private static volatile SecretKey instance;

    public EncryptionKeyHolder(@Value("${app.encryption-key}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY no está configurada — requerida para cifrar credenciales de pasarelas de pago. " +
                    "Genera una con: openssl rand -base64 32");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY debe decodificar a 32 bytes (AES-256), tiene " + keyBytes.length);
        }
        instance = new SecretKeySpec(keyBytes, "AES");
    }

    static SecretKey key() {
        if (instance == null) {
            throw new IllegalStateException("EncryptionKeyHolder no ha sido inicializado todavía por Spring");
        }
        return instance;
    }
}
