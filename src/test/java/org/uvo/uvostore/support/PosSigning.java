package org.uvo.uvostore.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.security.UvoHmac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * IMP-08. Las llaves y la firma UVO-HMAC de las pruebas del POS (contrato §3), en un solo sitio.
 *
 * <p>Las llaves de una conexión salen de su empresa: {@code in-<empresa>} (POS→tienda, la que verifica
 * {@code PosSignatureAuthFilter}) y {@code out-<empresa>} (tienda→POS, con la que firma {@code PosClient}). Así una
 * prueba firma sin tener que guardar nada aparte, y dos tiendas nunca comparten llave.
 */
public final class PosSigning {

    private PosSigning() {
    }

    /** 32 bytes que salen de la semilla, en base64: un secreto válido y reproducible. */
    public static String secret(String seed) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String inboundKeyId(PosConnection connection) {
        return "in-" + connection.getCompanyId();
    }

    public static String inboundSecret(PosConnection connection) {
        return secret("in-" + connection.getCompanyId());
    }

    public static String outboundKeyId(PosConnection connection) {
        return "out-" + connection.getCompanyId();
    }

    public static String outboundSecret(PosConnection connection) {
        return secret("out-" + connection.getCompanyId());
    }

    /** Las dos llaves de la conexión, ya guardada. Devuelve la misma conexión. */
    public static PosConnection installKeys(PosConnectionKeyRepository keys, PosConnection connection) {
        keys.save(key(connection, PosConnectionKey.Direction.INBOUND, inboundKeyId(connection), inboundSecret(connection)));
        keys.save(key(connection, PosConnectionKey.Direction.OUTBOUND, outboundKeyId(connection),
                outboundSecret(connection)));
        return connection;
    }

    public static PosConnectionKey key(PosConnection connection, PosConnectionKey.Direction direction, String keyId,
                                       String secret) {
        PosConnectionKey key = new PosConnectionKey();
        key.setConnection(connection);
        key.setDirection(direction);
        key.setKeyId(keyId);
        key.setSecret(secret);
        key.setCreatedAt(Instant.now());
        return key;
    }

    /** Un POST firmado por el POS con la llave de entrada de la conexión, como lo mandaría UvoPOS. */
    public static MockHttpServletRequestBuilder signedPost(String path, String body, PosConnection connection) {
        return signedPost(path, body, String.valueOf(connection.getCompanyId()), inboundKeyId(connection),
                inboundSecret(connection), System.currentTimeMillis() / 1000, UvoHmac.newNonce());
    }

    /** Lo mismo con todo explícito, para las pruebas que alteran una parte. */
    public static MockHttpServletRequestBuilder signedPost(String path, String body, String companyHeader, String keyId,
                                                           String secret, long timestamp, String nonce) {
        return signedPost(path, body, companyHeader, keyId, secret, timestamp, nonce, UvoHmac.CONTRACT_VERSION);
    }

    /** Con otra versión de contrato en {@code X-Uvo-Contract}: la cabecera va una sola vez. */
    public static MockHttpServletRequestBuilder signedPost(String path, String body, String companyHeader, String keyId,
                                                           String secret, long timestamp, String nonce,
                                                           String contract) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = UvoHmac.sign(secret, UvoHmac.canonical("POST", path, timestamp, nonce, keyId, bytes));
        return post(path)
                .header(UvoHmac.HEADER_KEY_ID, keyId)
                .header(UvoHmac.HEADER_TIMESTAMP, Long.toString(timestamp))
                .header(UvoHmac.HEADER_NONCE, nonce)
                .header(UvoHmac.HEADER_SIGNATURE, signature)
                .header(UvoHmac.HEADER_COMPANY, companyHeader)
                .header(UvoHmac.HEADER_CONTRACT, contract)
                .contentType("application/json")
                .content(bytes);
    }

    /** La llave tienda→POS de la conexión, para un {@code PosClient} de prueba. */
    public static org.uvo.uvostore.service.pos.PosSigningKeys fixedOutbound(String keyId, String secret) {
        return connection -> java.util.Optional.of(
                new org.uvo.uvostore.service.pos.PosSigningKeys.SigningKey(keyId, secret));
    }
}
