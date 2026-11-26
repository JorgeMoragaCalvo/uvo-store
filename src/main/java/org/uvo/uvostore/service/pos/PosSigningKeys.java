package org.uvo.uvostore.service.pos;

import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.Optional;

/**
 * IMP-08. De dónde saca {@link PosClient} la llave tienda→POS con la que firma. En la aplicación es
 * {@link PosConnectionKeyService}; las pruebas de {@code PosClient} pasan una fija.
 */
@FunctionalInterface
public interface PosSigningKeys {

    Optional<SigningKey> outboundFor(PosConnection connection);

    /** Un {@code key_id} y su secreto base64. {@code toString} oculta el secreto. */
    record SigningKey(String keyId, String secret) {

        @Override
        public String toString() {
            return "SigningKey[keyId=" + keyId + ", secret=***]";
        }
    }
}
