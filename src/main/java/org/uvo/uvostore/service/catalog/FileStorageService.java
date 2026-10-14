package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;

// Local-disk/S3-interchangeable Resource abstraction, replacing Laravel's
// $file->store('products', 'public') (app/Livewire/Admin/Products/ProductCreate.php:184/197).
// A local-disk implementation is the direct equivalent of the Laravel call above; an S3-backed
// implementation is a drop-in swap behind this same interface with no ProductImageService change.
//
// store()/delete() take/return a storage-relative key (e.g. "products/3/xxxx.jpg"), never a full
// URL — that's what gets persisted in entity columns (ProductImage.imagePath, Category.image,
// etc.), so switching drivers never requires a data migration. publicUrl() is the one place that
// turns a stored key into whatever a browser can actually load: a local implementation builds an
// absolute URL on the CURRENT STORE's public API origin (StorePublicUrlResolver, served by WebConfig's
// resource handler at "/uploads/**"), an S3 implementation builds the bucket/CDN URL, which doesn't
// depend on the store. Callers must always go through publicUrl() when building a DTO field meant
// to be rendered as an <img src> — never concatenate "/uploads/" themselves (Fase 4).
//
// PROD-05b: the local driver reads the store from TenantContext, so publicUrl() is request-bound like
// the S3 driver's store(). It FAILS without a store rather than returning a relative path. Code that
// runs outside a request (a listener, a scheduled job) has to wrap the call in
// TenantContext.runWithin(store, ...).
public interface FileStorageService {
    String store(MultipartFile file, String directory);
    void delete(String path);
    String publicUrl(String path);
}
