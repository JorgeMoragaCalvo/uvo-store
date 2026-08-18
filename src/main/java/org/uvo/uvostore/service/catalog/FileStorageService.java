package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;

// Local-disk/S3-interchangeable Resource abstraction, replacing Laravel's
// $file->store('products', 'public') (app/Livewire/Admin/Products/ProductCreate.php:184/197).
// A local-disk implementation is the direct equivalent of the Laravel call above; an S3-backed
// implementation is a drop-in swap behind this same interface with no ProductImageService change.
public interface FileStorageService {
    String store(MultipartFile file, String directory);
    void delete(String path);
}
