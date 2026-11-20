package dev.dineshrama.relay;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

@Component
@ConditionalOnProperty(name = "relay.storage", havingValue = "s3")
public class S3Storage implements Storage, AutoCloseable {
  private final S3Client client;
  private final String bucket;

  public S3Storage(RelayProperties p) {
    var builder =
        S3Client.builder()
            .region(Region.of(p.s3Region()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(10))
                    .socketTimeout(Duration.ofSeconds(60)))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofMinutes(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(75)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    if (p.s3Endpoint() != null && !p.s3Endpoint().isBlank())
      builder.endpointOverride(URI.create(p.s3Endpoint()));
    client = builder.build();
    bucket = p.s3Bucket();
  }

  public String kind() {
    return "s3";
  }

  public String putPart(UUID id, int number, Path staged) {
    String key = "relay/parts/" + id + "/" + number + "-" + UUID.randomUUID();
    client.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromFile(staged));
    return key;
  }

  public InputStream openPart(String key) {
    return client.getObject(r -> r.bucket(bucket).key(key));
  }

  public void assemble(UUID id, List<Transfer.Part> parts, long size) {
    String key = "relay/objects/" + id;
    String upload =
        client
            .createMultipartUpload(
                r -> r.bucket(bucket).key(key).contentType("application/octet-stream"))
            .uploadId();
    try {
      List<CompletedPart> receipts = new ArrayList<>();
      for (Transfer.Part part : parts) {
        var response =
            client.uploadPartCopy(
                r ->
                    r.bucket(bucket)
                        .key(key)
                        .uploadId(upload)
                        .partNumber(part.number())
                        .copySource(bucket + "/" + part.key()));
        receipts.add(
            CompletedPart.builder()
                .partNumber(part.number())
                .eTag(response.copyPartResult().eTag())
                .build());
      }
      client.completeMultipartUpload(
          r ->
              r.bucket(bucket)
                  .key(key)
                  .uploadId(upload)
                  .multipartUpload(CompletedMultipartUpload.builder().parts(receipts).build()));
    } catch (RuntimeException e) {
      try {
        client.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(upload));
      } catch (RuntimeException ignored) {
      }
      throw e;
    }
  }

  public InputStream openObject(UUID id) {
    return client.getObject(r -> r.bucket(bucket).key("relay/objects/" + id));
  }

  public void discardObject(UUID id) {
    client.deleteObject(r -> r.bucket(bucket).key("relay/objects/" + id));
  }

  public void cleanupParts(UUID id) {
    String prefix = "relay/parts/" + id + "/";
    for (var page : client.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)))
      for (var o : page.contents()) client.deleteObject(r -> r.bucket(bucket).key(o.key()));
  }

  public void close() {
    client.close();
  }
}
