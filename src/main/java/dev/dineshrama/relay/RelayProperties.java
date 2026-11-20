package dev.dineshrama.relay;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("relay")
public record RelayProperties(
    String adminToken,
    Path dataDirectory,
    String storage,
    long chunkSize,
    long maxFileSize,
    int maxConcurrentUploads,
    int maxOpenTransfers,
    long expiryHours,
    String s3Bucket,
    String s3Region,
    String s3Endpoint) {
  public RelayProperties {
    if (adminToken == null || adminToken.length() < 24)
      throw new IllegalArgumentException("ADMIN_TOKEN must contain at least 24 characters");
    if (!"local".equals(storage) && !"s3".equals(storage))
      throw new IllegalArgumentException("Storage must be local or s3");
    if (chunkSize < 5L * 1024 * 1024 || chunkSize > 64L * 1024 * 1024)
      throw new IllegalArgumentException("Chunk size must be 5–64 MiB");
    if (maxFileSize < 1 || maxFileSize > chunkSize * 10000L)
      throw new IllegalArgumentException("File limit exceeds supported part count");
    if (maxConcurrentUploads < 1
        || maxConcurrentUploads > 8
        || maxOpenTransfers < 1
        || expiryHours < 1) throw new IllegalArgumentException("Invalid capacity limits");
    if ("s3".equals(storage) && (s3Bucket == null || s3Bucket.isBlank()))
      throw new IllegalArgumentException("S3_BUCKET is required");
  }
}
