package org.uvo.uvostore.service.catalog;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

// A8: uploads used to take their extension straight from the client's own getOriginalFilename()
// with no allow-list of any kind, and /uploads/** is served publicly from the same origin as the
// API. An .html or .svg with script in it, uploaded through any of the six admin upload endpoints,
// came back as renderable same-origin content — stored XSS. The client-declared content type was
// no safer, since the client sets that too.
//
// So neither the filename nor the declared type is trusted: the first bytes decide what the file
// is, and the extension is derived from THAT. Anything not on the list is rejected outright.
//
// Lives in the storage layer on purpose. Every upload path in the app (products, categories, user
// avatars, home banners, store settings) funnels through FileStorageService.store(), so validating
// here means no caller can forget to.
@Component
public class UploadedImageValidator {

    public enum ImageKind {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png"),
        GIF("image/gif", ".gif"),
        WEBP("image/webp", ".webp");

        private final String contentType;
        private final String extension;

        ImageKind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }
    }

    // Enough bytes for the longest signature we check (WEBP needs 12).
    private static final int HEADER_BYTES = 12;

    public ImageKind validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }

        byte[] header = readHeader(file);
        ImageKind kind = detect(header);
        if (kind == null) {
            throw new IllegalArgumentException(
                    "El archivo no es una imagen válida. Formatos permitidos: JPG, PNG, GIF y WEBP.");
        }
        return kind;
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo subido", e);
        }
    }

    private ImageKind detect(byte[] h) {
        if (h.length < 4) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return ImageKind.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (h.length >= 8 && Arrays.equals(Arrays.copyOf(h, 8), png)) {
            return ImageKind.PNG;
        }
        // GIF: "GIF87a" / "GIF89a"
        if (h.length >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return ImageKind.GIF;
        }
        // WEBP: "RIFF" .... "WEBP" — the four size bytes in between are not part of the signature.
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return ImageKind.WEBP;
        }
        return null;
    }
}
