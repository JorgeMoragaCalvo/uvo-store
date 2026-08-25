package org.uvo.uvostore.storage;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.service.catalog.LocalFileStorageService;
import org.uvo.uvostore.service.catalog.S3FileStorageServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Plain unit tests (no Spring context, no network) — publicUrl() is pure string-building, and the
// S3Client itself doesn't connect to anything until a request is actually issued, so this is safe
// to exercise without live AWS/MinIO credentials. store()/delete() aren't covered here for the same
// reason ChilexpressQuoteClient's HTTP call isn't unit-tested — no sandbox to test against, see
// S3FileStorageServiceImpl's class comment.
class FileStorageServiceTest {

    private final LocalFileStorageService local = new LocalFileStorageService("uploads");

    @Test
    void localDriverBuildsASameOriginUploadsUrl() {
        assertEquals("/uploads/products/1/abc.jpg", local.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void localDriverReturnsNullForNullPath() {
        assertNull(local.publicUrl(null));
    }

    @Test
    void s3DriverBuildsDefaultVirtualHostedUrlWhenNoOverridesAreConfigured() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl("my-bucket", "us-east-1", "", "", "", "");
        assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverPrefersThePublicBaseUrlOverrideWhenSet() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl(
                "my-bucket", "us-east-1", "", "", "", "https://cdn.example.com/");
        assertEquals("https://cdn.example.com/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverUsesPathStyleUrlsForAnEndpointOverrideLikeMinioOrSpaces() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl(
                "my-bucket", "us-east-1", "", "", "https://minio.internal:9000", "");
        assertEquals("https://minio.internal:9000/my-bucket/products/1/abc.jpg", s3.publicUrl("products/1/abc.jpg"));
    }

    @Test
    void s3DriverReturnsNullForNullPath() {
        S3FileStorageServiceImpl s3 = new S3FileStorageServiceImpl("my-bucket", "us-east-1", "", "", "", "");
        assertNull(s3.publicUrl(null));
    }
}
