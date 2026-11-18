package org.uvo.uvostore.service.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * IMP-06. La huella de un token de acceso que guarda la fila del outbox en su lugar.
 *
 * <p>La fila tiene que poder decir «este correo era para el token que había entonces» sin guardar el token: un
 * volcado de la tabla, un log de la consulta o el panel de envíos fallidos no deben dar acceso a nadie. El token son
 * 128 o más bits aleatorios, así que su SHA-256 no se puede revertir y no hace falta sal.
 */
public final class AccessTokenHash {

    private AccessTokenHash() {
    }

    public static String of(String token) {
        if (token == null) {
            throw new IllegalArgumentException("No hay token del que sacar la huella");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no trae SHA-256", e);
        }
    }

    /** Si {@code token} es el que dio origen a {@code hash}; false si ya no hay token. */
    public static boolean matches(String token, String hash) {
        return token != null && hash != null
                && MessageDigest.isEqual(of(token).getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII));
    }
}
