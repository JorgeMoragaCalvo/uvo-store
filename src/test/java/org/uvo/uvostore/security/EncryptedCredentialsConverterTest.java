package org.uvo.uvostore.security;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.payment.EncryptedCredentialsConverter;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B10. El {@code EncryptedCredentialsConverter} es lo que mantiene fuera de la base las claves de
 * Stripe, Webpay y MercadoPago de cada tienda, y no tenía ningún test. Un converter que se rompe no
 * avisa: sigue guardando y leyendo, solo que en claro.
 */
class EncryptedCredentialsConverterTest extends IntegrationTestSupport {

    private static final String SECRET_VALUE = "sk_live_no-deberia-aparecer-en-la-columna";

    private final EncryptedCredentialsConverter converter = new EncryptedCredentialsConverter();

    @Autowired
    private PaymentGatewayConfigRepository paymentGatewayConfigRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Lo que se guarda se recupera igual")
    void whatGoesInComesBackOut() {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("secretKey", SECRET_VALUE);
        credentials.put("publicKey", "pk_live_123");

        String stored = converter.convertToDatabaseColumn(credentials);

        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(credentials);
    }

    @Test
    @DisplayName("Dos cifrados del mismo valor no son iguales")
    void encryptingTheSameValueTwiceGivesDifferentCiphertext() {
        Map<String, String> credentials = Map.of("secretKey", SECRET_VALUE);

        // El IV es aleatorio por operación. Si esto fallara, dos tiendas con la misma clave tendrían
        // la misma columna y bastaría comparar filas para deducirlo.
        assertThat(converter.convertToDatabaseColumn(credentials))
                .isNotEqualTo(converter.convertToDatabaseColumn(credentials));
    }

    @Test
    @DisplayName("Un null sigue siendo null, no una cadena cifrada")
    void nullIsPreserved() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("La columna de la base no contiene el secreto en claro")
    void theDatabaseColumnNeverHoldsThePlaintext() {
        Store store = createStore("cred-enc");
        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setStore(store);
        config.setGateway(PaymentGatewayType.STRIPE);
        config.setEnabled(true);
        config.setCredentials(Map.of("secretKey", SECRET_VALUE));
        paymentGatewayConfigRepository.saveAndFlush(config);

        // Se lee la columna cruda, sin pasar por el converter: es la única forma de comprobar lo que
        // de verdad quedó escrito.
        String raw = (String) entityManager
                .createNativeQuery("SELECT credentials FROM payment_gateway_configs WHERE id = :id")
                .setParameter("id", config.getId())
                .getSingleResult();

        assertThat(raw).doesNotContain(SECRET_VALUE);
        assertThat(raw).doesNotContain("secretKey");

        entityManager.clear();
        assertThat(paymentGatewayConfigRepository.findById(config.getId()).orElseThrow().getCredentials())
                .containsEntry("secretKey", SECRET_VALUE);
    }

    @Test
    @DisplayName("Un texto cifrado con otra clave no se descifra: falla en vez de devolver basura")
    void ciphertextFromAnotherKeyIsRefused() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(encryptWith(otherKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudieron descifrar");
    }

    @Test
    @DisplayName("Manipular el texto cifrado se detecta: GCM autentica, no solo cifra")
    void tamperedCiphertextIsRefused() {
        String stored = converter.convertToDatabaseColumn(Map.of("secretKey", SECRET_VALUE));

        byte[] bytes = Base64.getDecoder().decode(stored);
        bytes[bytes.length - 1] ^= 0x01;   // un bit del último byte
        String tampered = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Una columna con basura no cifrada falla con un mensaje que no filtra nada")
    void garbageInTheColumnIsRefused() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("esto-no-es-base64-cifrado"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudieron descifrar las credenciales de la pasarela");
    }

    /** Cifra con el mismo formato del converter (IV de 12 bytes por delante) pero con otra clave. */
    private String encryptWith(byte[] key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal("{\"secretKey\":\"otra-cosa\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
    }
}
