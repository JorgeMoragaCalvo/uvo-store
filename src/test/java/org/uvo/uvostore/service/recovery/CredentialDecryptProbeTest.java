package org.uvo.uvostore.service.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.settings.SecretCrypto;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-09. La sonda de descifrado distingue un valor que la llave abre de uno cifrado con otra llave, que es lo que deja
 * una restauración con la {@code APP_ENCRYPTION_KEY} equivocada.
 *
 * <p>Se mide contra lo que ya había: la suite corre sobre la base local, que tiene credenciales del desarrollo cifradas
 * con otra llave (ver {@code src/test/resources/config/application.properties}).
 */
class CredentialDecryptProbeTest extends IntegrationTestSupport {

    @Autowired
    private CredentialDecryptProbe probe;

    @Test
    @DisplayName("Un secreto cifrado con la llave vigente abre; uno cifrado con otra llave cuenta como falla")
    void countsWhatTheKeyCannotOpen() throws Exception {
        CredentialDecryptProbe.Result before = probe.probe();

        Store good = createStore("sonda-ok");
        setSetting(good, "stripe_secret_key", SecretCrypto.encrypt("sk_test_bueno"));
        CredentialDecryptProbe.Result afterGood = probe.probe();
        assertThat(afterGood.failures()).isEqualTo(before.failures());
        assertThat(afterGood.sources().get("settings.stripe").checked())
                .isEqualTo(before.sources().get("settings.stripe").checked() + 1);

        Store bad = createStore("sonda-mala");
        setSetting(bad, "stripe_secret_key", encryptWithAnotherKey("sk_test_de_otro_ambiente"));
        CredentialDecryptProbe.Result afterBad = probe.probe();
        assertThat(afterBad.failures()).isEqualTo(before.failures() + 1);
        assertThat(afterBad.sources().get("settings.stripe").failed())
                .isEqualTo(before.sources().get("settings.stripe").failed() + 1);
        assertThat(probe.lastResult()).isEqualTo(afterBad);
    }

    /** El mismo formato que SecretCrypto (IV de 12 bytes y AES-256-GCM), con una llave que no es la de la suite. */
    private static String encryptWithAnotherKey(String plaintext) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey otherKey = generator.generateKey();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, otherKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length)
                .put(iv).put(ciphertext).array());
    }
}
