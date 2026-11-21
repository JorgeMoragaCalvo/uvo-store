import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * IMP-09. Solo para el ensayo (ensayo.sh, paso C10): cifra un valor como lo hace la aplicación (SecretCrypto,
 * AES-256-GCM, base64 de IV de 12 bytes y texto cifrado), para dejar en la base un secreto que la sonda de descifrado
 * tenga que abrir. Se corre como archivo suelto (LLAVE=<base64> java CifrarSecreto.java), con el texto por la entrada
 * estándar; la llave llega por la variable LLAVE, nunca como argumento.
 */
public class CifrarSecreto {
    public static void main(String[] args) throws Exception {
        byte[] llave = Base64.getDecoder().decode(System.getenv("LLAVE"));
        String texto = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(llave, "AES"), new GCMParameterSpec(128, iv));
        byte[] cifrado = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        System.out.println(Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array()));
    }
}
