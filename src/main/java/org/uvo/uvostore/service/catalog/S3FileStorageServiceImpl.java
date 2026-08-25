package org.uvo.uvostore.service.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.security.TenantContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

// Fase 4: cloud storage for merchant-uploaded images, on top of the AWS SDK v2 S3 client — which,
// via endpointOverride, also talks to any S3-compatible provider (MinIO, DigitalOcean Spaces,
// Cloudflare R2), not only real AWS S3. One shared bucket for the whole platform, same
// "platform-level infra + per-store data" split already used for Webpay (parent commerce code) and
// MercadoPago — keys are prefixed with the store id (`{directory}/{storeId}/{uuid}.ext`) so nothing
// further is needed to keep tenants' files apart within the single bucket.
//
// This client deliberately does NOT set an object ACL on upload: AWS disabled ACLs by default for
// buckets created since April 2023 ("Bucket owner enforced"), so a PutObject call carrying
// `public-read` would fail outright on a modern bucket. Instead, the operator is expected to
// configure public read access via a bucket policy (or leave the bucket private and put a CDN with
// its own access control in front of it, overriding publicUrl() output via app.storage.s3.
// public-base-url). Store()/delete() failures propagate as unchecked exceptions — same as
// LocalFileStorageService — a broken upload must fail the request, not silently succeed with a
// missing image.
@Service
@ConditionalOnProperty(prefix = "app.storage", name = "driver", havingValue = "s3")
public class S3FileStorageServiceImpl implements FileStorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final String region;
    private final String endpointOverride;
    private final String publicBaseUrl;

    public S3FileStorageServiceImpl(
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.region:us-east-1}") String region,
            @Value("${app.storage.s3.access-key:}") String accessKey,
            @Value("${app.storage.s3.secret-key:}") String secretKey,
            @Value("${app.storage.s3.endpoint:}") String endpointOverride,
            @Value("${app.storage.s3.public-base-url:}") String publicBaseUrl) {
        this.bucket = bucket;
        this.region = region;
        this.endpointOverride = endpointOverride.isBlank() ? null : endpointOverride;
        this.publicBaseUrl = publicBaseUrl.isBlank() ? null : stripTrailingSlash(publicBaseUrl);
        this.s3Client = buildClient(region, accessKey, secretKey, this.endpointOverride);
    }

    @Override
    public String store(MultipartFile file, String directory) {
        Long storeId = TenantContext.requireStoreId();
        String extension = "";
        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf('.'));
        }
        String key = directory + "/" + storeId + "/" + UUID.randomUUID() + extension;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file for S3 upload", e);
        }

        return key;
    }

    @Override
    public void delete(String path) {
        if (path == null || path.isBlank()) return;
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(path).build());
    }

    @Override
    public String publicUrl(String path) {
        if (path == null) return null;
        if (publicBaseUrl != null) {
            return publicBaseUrl + "/" + path;
        }
        if (endpointOverride != null) {
            // Path-style, the common convention for MinIO/self-hosted S3-compatible endpoints.
            return stripTrailingSlash(endpointOverride) + "/" + bucket + "/" + path;
        }
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + path;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static S3Client buildClient(String region, String accessKey, String secretKey, String endpointOverride) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        if (endpointOverride != null) {
            builder.endpointOverride(URI.create(endpointOverride)).forcePathStyle(true);
        }
        return builder.build();
    }
}
