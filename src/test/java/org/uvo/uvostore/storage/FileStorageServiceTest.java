package org.uvo.uvostore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.catalog.LocalFileStorageService;
import org.uvo.uvostore.service.catalog.S3FileStorageServiceImpl;
import org.uvo.uvostore.service.catalog.UploadedImageValidator;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Plain unit tests (no Spring context, no network) — publicUrl() is pure string-building, and the
// S3Client itself doesn't connect to anything until a request is actually issued, so this is safe
// to exercise without live AWS/MinIO credentials. store()/delete() aren't covered here for the same
// reason ChilexpressQuoteClient's HTTP call isn't unit-tested — no sandbox to test against, see
// S3FileStorageServiceImpl's class comment.
class FileStorageServiceTest {

    // The validator is irrelevant to these cases: they only exercise publicUrl(), which is pure
    // string-building and never sees an uploaded file.
    private static final UploadedImageValidator VALIDATOR = new UploadedImageValidator();

    // Como en producción: el storefront y la API son el mismo origen https tras el proxy.
    private static final StorePublicUrlResolver PUBLIC_URLS =
            new StorePublicUrlResolver("uvostore.cl", "https://{host}", "https://{host}");

    private final LocalFileStorageService local = new LocalFileStorageService("uploads", VALIDATOR, PUBLIC_URLS);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static Store store(String slug, String domain, Instant verifiedAt) {
        return Store.builder().id(1L).name("Tienda").slug(slug).domain(domain)
                .domainVerifiedAt(verifiedAt).build();
    }

    private String publicUrlWithin(Store store, String path) {
        String[] result = new String[1];
        TenantContext.runWithin(store, () -> result[0] = local.publicUrl(path));
        return result[0];
    }

    // PROD-05b. Antes la URL salía de getScheme()/getServerName()/getServerPort() de la petición en curso,
    // que detrás de un proxy que termina TLS describe el tramo interno (http y el puerto del upstream). Aquí
    // no hay petición ninguna: lo que se afirma es que la URL sale de la tienda, no de dónde llegó la llamada.
    @Test
    void localDriverBuildsAnAbsoluteUrlOnTheStoresPublicApiOrigin() {
        assertEquals("https://juan.uvostore.cl/uploads/products/1/abc.jpg",
                publicUrlWithin(store("juan", null, null), "products/1/abc.jpg"));
    }

    @Test
    void localDriverUsesTheVerifiedCustomDomainAndIgnoresAnUnverifiedOne() {
        assertEquals("https://tiendadejuan.cl/uploads/products/1/abc.jpg",
                publicUrlWithin(store("juan", "tiendadejuan.cl", Instant.now()), "products/1/abc.jpg"));
        // Sin verificar, la imagen saldría de un dominio cuyo DNS todavía no apunta aquí: imagen rota.
        assertEquals("https://juan.uvostore.cl/uploads/products/1/abc.jpg",
                publicUrlWithin(store("juan", "tiendadejuan.cl", null), "products/1/abc.jpg"));
    }

    @Test
    void localDriverGivesEachStoreItsOwnHost() {
        assertEquals("https://ana.uvostore.cl/uploads/a.jpg", publicUrlWithin(store("ana", null, null), "a.jpg"));
        assertEquals("https://luis.uvostore.cl/uploads/a.jpg", publicUrlWithin(store("luis", null, null), "a.jpg"));
    }

    @Test
    void localDriverReturnsNullForNullPathEvenWithoutAStore() {
        assertNull(local.publicUrl(null));
    }

    // Antes, sin petición devolvía "/uploads/…" relativo, que en producción es justo la URL que rompe la
    // imagen (se resuelve contra el origen de la página). Ahora un llamador sin tienda se entera.
    @Test
    void localDriverFailsWithoutAStoreInsteadOfReturningARelativeUrl() {
        assertThrows(BusinessException.class, () -> local.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverBuildsDefaultVirtualHostedUrlWhenNoOverridesAreConfigured() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl("my-bucket", "us-east-1", "", "", "", "", VALIDATOR);
        assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverPrefersThePublicBaseUrlOverrideWhenSet() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl(
                "my-bucket", "us-east-1", "", "", "", "https://cdn.example.com/", VALIDATOR);
        assertEquals("https://cdn.example.com/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverUsesPathStyleUrlsForAnEndpointOverrideLikeMinioOrSpaces() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl(
                "my-bucket", "us-east-1", "", "", "https://minio.internal:9000", "", VALIDATOR);
        assertEquals("https://minio.internal:9000/my-bucket/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverReturnsNullForNullPath() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl("my-bucket", "us-east-1", "", "", "", "", VALIDATOR);
        assertNull(s3.publicUrl(null));
    }
}
