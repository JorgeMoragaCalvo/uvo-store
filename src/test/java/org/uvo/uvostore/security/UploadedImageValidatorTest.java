package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.uvo.uvostore.service.catalog.UploadedImageValidator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A8. The point of these: neither the filename nor the declared content type decides anything —
// the bytes do. Plain JUnit, the validator has no dependencies.
class UploadedImageValidatorTest {

    private final UploadedImageValidator validator = new UploadedImageValidator();

    @Test
    @DisplayName("Un PNG real se acepta y su extensión sale de los bytes, no del nombre")
    void realPngIsAcceptedAndTyped() throws IOException {
        // Named .txt on purpose: the filename must not matter.
        var kind = validator.validate(new MockMultipartFile("f", "cualquiera.txt", "text/plain", png()));

        assertThat(kind).isEqualTo(UploadedImageValidator.ImageKind.PNG);
        assertThat(kind.extension()).isEqualTo(".png");
        assertThat(kind.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Un JPEG real se detecta como JPEG")
    void realJpegIsDetected() throws IOException {
        var kind = validator.validate(new MockMultipartFile("f", "foto.jpg", "image/jpeg", jpeg()));
        assertThat(kind).isEqualTo(UploadedImageValidator.ImageKind.JPEG);
    }

    @Test
    @DisplayName("HTML disfrazado de PNG se rechaza — es el vector de XSS almacenada")
    void htmlPretendingToBeAnImageIsRejected() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("f", "inocente.png", "image/png", html)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es una imagen válida");
    }

    @Test
    @DisplayName("Un SVG se rechaza: es XML ejecutable, no un formato de mapa de bits permitido")
    void svgIsRejected() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("f", "logo.svg", "image/svg+xml", svg)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Un archivo vacío se rechaza")
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("f", "v.png", "image/png", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vacío");
    }

    @Test
    @DisplayName("Un nombre con separadores de ruta es inofensivo: el nombre no se usa para nada")
    void pathTraversalInTheFilenameIsIrrelevant() throws IOException {
        var kind = validator.validate(
                new MockMultipartFile("f", "../../../etc/passwd.png", "image/png", png()));
        assertThat(kind.extension()).isEqualTo(".png");
    }

    private byte[] png() throws IOException {
        return encode("png");
    }

    private byte[] jpeg() throws IOException {
        return encode("jpg");
    }

    private byte[] encode(String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
}
