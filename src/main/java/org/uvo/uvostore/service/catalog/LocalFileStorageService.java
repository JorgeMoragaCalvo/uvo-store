package org.uvo.uvostore.service.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    public LocalFileStorageService(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file, String directory) {
        try {
            Path targetDir = uploadRoot.resolve(directory);
            Files.createDirectories(targetDir);

            String extension = "";
            String originalName = file.getOriginalFilename();
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
            }
            String filename = UUID.randomUUID() + extension;

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

    @Override
    public String publicUrl(String path) {
        return path == null ? null : "/uploads/" + path;
    }
}