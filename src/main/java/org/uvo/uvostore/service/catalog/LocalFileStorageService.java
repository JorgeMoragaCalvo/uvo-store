package org.uvo.uvostore.service.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

// Local-disk implementation of FileStorageService, the direct equivalent of Laravel's
// Storage::disk('public')->store($directory, $file) (app/Livewire/Admin/Products/ProductCreate.php
// :184/197) — writes under app.upload-dir/{directory}/ with a random filename, preserving the
// original extension, and returns a path relative to the upload root (matching the relative
// "products/xxxx.jpg" path Laravel's store() call returns).
//
// Default driver (dev/CI-friendly, no cloud credentials required) — set app.storage.driver=s3 to
// switch to S3FileStorageServiceImpl instead (Fase 4).
@Service
@ConditionalOnProperty(prefix = "app.storage", name = "driver", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path uploadRoot;
    private final UploadedImageValidator imageValidator;
    private final StorePublicUrlResolver publicUrls;

    public LocalFileStorageService(@Value("${app.upload-dir:uploads}") String uploadDir,
                                   UploadedImageValidator imageValidator,
                                   StorePublicUrlResolver publicUrls) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
        this.imageValidator = imageValidator;
        this.publicUrls = publicUrls;
    }

    @Override
    public String store(MultipartFile file, String directory) {
        // A8: the extension now comes from the file's own magic bytes, never from the client's
        // getOriginalFilename() — which had no allow-list and could carry path separators.
        UploadedImageValidator.ImageKind kind = imageValidator.validate(file);
        try {
            Path targetDir = uploadRoot.resolve(directory);
            Files.createDirectories(targetDir);

            String filename = UUID.randomUUID() + kind.extension();

            Path target = targetDir.resolve(filename);
            file.transferTo(target);

            return directory + "/" + filename;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }
    }

    @Override
    public void delete(String path) {
        if(path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(uploadRoot.resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file", e);
        }
    }

    // Must be an ABSOLUTE URL, not root-relative: the frontend and this API are on different
    // origins (e.g. the Vite dev server vs. demo.localhost:8080, or a static-hosted SPA vs. its
    // API domain in production). A root-relative "/uploads/..." resolves against whatever origin
    // the browser page is on — the frontend's — which doesn't serve that path; the browser gets
    // that dev server's SPA-fallback HTML back instead of image bytes and shows a broken image.
    //
    // PROD-05b. El origen sale de StorePublicUrlResolver.apiOrigin(), el mismo que usan los retornos de
    // pasarela y los webhooks, y NO de la petición en curso. Antes se armaba con getScheme()/
    // getServerName()/getServerPort(): detrás de un proxy que termina TLS eso describe el tramo interno
    // (http y el puerto del upstream), así que el navegador recibía imágenes en http://…:8080 desde una
    // página https — contenido mixto bloqueado — y no se podía arreglar con server.forward-headers-
    // strategy (ver ClientIpResolver: confiaría en cabeceras del cliente).
    //
    // La tienda se lee de TenantContext y no se recibe por parámetro, a propósito: los nueve sitios de
    // llamada son código de petición que ya está dentro de una tienda (los servicios de banners y de
    // ajustes la leen en el mismo método), los mappers son estáticos y recibirían la tienda solo para
    // pasarla, y S3FileStorageServiceImpl.store() ya la toma de aquí. Sin tienda FALLA, en vez de devolver
    // "/uploads/…" relativo como hacía sin petición: en producción esa URL es justo la que rompe la
    // imagen. Un oyente o un trabajo que necesite una URL debe correr dentro de TenantContext.runWithin().
    @Override
    public String publicUrl(String path) {
        if (path == null) return null;
        return publicUrls.apiUrl(TenantContext.requireCurrent(), "/uploads/" + path);
    }
}