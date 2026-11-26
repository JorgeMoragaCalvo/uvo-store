package org.uvo.uvostore.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * IMP-08. La firma UVO-HMAC-SHA256 del contrato UvoPOS ↔ uvo-store (§3), en las dos direcciones: la usan
 * {@code PosClient} para firmar lo que sale al POS y {@link PosSignatureAuthFilter} para verificar lo que llega.
 *
 * <p>Es el mismo esquema que AlfozApi ya usa con uvocms ({@code FirmaIntegracion}). La canónica son siete líneas
 * separadas por {@code \n}, sin salto final:
 *
 * <pre>
 * UVO-HMAC-SHA256
 * {MÉTODO}
 * {RUTA}                la que viaja, cruda: sin dominio, sin query y sin decodificar
 * {TIMESTAMP}           segundos unix
 * {NONCE}               base64url de 22 a 64 caracteres
 * {KEY_ID}
 * {hex(sha256(cuerpo))} de los bytes crudos; sin cuerpo, sha256("")
 * </pre>
 *
 * <p>La firma es {@code hex(HMAC-SHA256(base64_decode(secreto), canónica))} en minúsculas, y viaja como
 * {@code v1=<hex>}. El vector de prueba está en {@code docs/contrato-pos-ecommerce/ejemplos/firma-vector.json}.
 */
public final class UvoHmac {

    public static final String SCHEME = "UVO-HMAC-SHA256";
    public static final String SIGNATURE_PREFIX = "v1=";

    public static final String HEADER_KEY_ID = "X-Uvo-Key-Id";
    public static final String HEADER_TIMESTAMP = "X-Uvo-Timestamp";
    public static final String HEADER_NONCE = "X-Uvo-Nonce";
    public static final String HEADER_SIGNATURE = "X-Uvo-Signature";
    public static final String HEADER_COMPANY = "X-Company-ID";
    public static final String HEADER_CONTRACT = "X-Uvo-Contract";

    /** La versión mayor del contrato que habla esta tienda (§1). */
    public static final String CONTRACT_VERSION = "1";

    /** Cuánto puede alejarse el timestamp del reloj local, hacia atrás o hacia adelante (§3, regla 1). */
    public static final long WINDOW_SECONDS = 300;

    /** Mínimo de bytes del secreto ya decodificado (§2). */
    public static final int MIN_SECRET_BYTES = 32;

    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{22,64}$");
    private static final Pattern SIGNATURE = Pattern.compile("^v1=[0-9a-f]{64}$");
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private UvoHmac() {
    }

    public static String canonical(String method, String rawPath, long timestamp, String nonce, String keyId,
                                   byte[] body) {
        return String.join("\n", SCHEME, method, rawPath, Long.toString(timestamp), nonce, keyId, sha256Hex(body));
    }

    /** {@code v1=} más el HMAC en hex minúsculas. */
    public static String sign(String secretBase64, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(decodeSecret(secretBase64), "HmacSHA256"));
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular la firma UVO-HMAC", e);
        }
    }

    /**
     * Compara en tiempo constante. Solo vale la forma exacta {@code v1=} más 64 hexadecimales en minúsculas: no se
     * normaliza nada (§3, regla 2).
     */
    public static boolean verify(String secretBase64, String canonical, String received) {
        if (received == null || !SIGNATURE.matcher(received).matches()) {
            return false;
        }
        String expected = sign(secretBase64, canonical);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                received.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static boolean isValidNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    public static boolean isValidKeyId(String keyId) {
        return keyId != null && KEY_ID.matcher(keyId).matches();
    }

    /** Un nonce nuevo: 24 bytes aleatorios en base64url sin relleno (32 caracteres). */
    public static String newNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Si el secreto es base64 válido de al menos {@value #MIN_SECRET_BYTES} bytes. El mensaje de quien llama no debe
     * repetir el valor.
     */
    public static boolean isUsableSecret(String secretBase64) {
        try {
            return secretBase64 != null && decodeSecret(secretBase64).length >= MIN_SECRET_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decodeSecret(String secretBase64) {
        return Base64.getDecoder().decode(secretBase64.trim());
    }
}
