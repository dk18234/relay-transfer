package dev.dineshrama.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferIntegrationTest {
  static final String TOKEN = "relay-integration-test-token-12345";
  static final EmbeddedPostgres PG;
  static final Path DATA;

  static {
    try {
      PG = EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").start();
      DATA = Files.createTempDirectory("relay-integration-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
    r.add("relay.admin-token", () -> TOKEN);
    r.add("relay.data-directory", () -> DATA.toString());
    r.add("relay.worker-initial-delay-ms", () -> "99999999");
  }

  @Autowired TransferService service;
  @Autowired TransferStore store;
  @Autowired Storage storage;
  @Autowired RelayProperties config;
  @Autowired TransactionTemplate tx;
  @Autowired MeterRegistry metrics;
  @Autowired TestRestTemplate http;

  @BeforeEach
  void reset() {
    store.db().execute("TRUNCATE transfer_event,part,transfer CASCADE");
  }

  static String hash(byte[] b) {
    var d = Hashes.sha256();
    d.update(b);
    return Hashes.hex(d);
  }

  Transfer create(byte[] b) {
    return service.create(
        new TransferService.Create("dataset.bin", b.length, hash(b), UUID.randomUUID().toString()));
  }

  void upload(Transfer t, int n, byte[] b) throws IOException {
    service.upload(t.id(), n, b.length, hash(b), new ByteArrayInputStream(b));
  }

  byte[] bytes(int n) {
    byte[] b = new byte[n];
    new Random(42).nextBytes(b);
    return b;
  }

  ApiException error(int status, org.junit.jupiter.api.function.Executable f) {
    ApiException e = assertThrows(ApiException.class, f);
    assertEquals(status, e.status);
    return e;
  }

  HttpHeaders auth() {
    var h = new HttpHeaders();
    h.set("X-Admin-Token", TOKEN);
    return h;
  }

  @Test
  void createIsDurableAndIdempotent() {
    byte[] b = bytes(37);
    var c = new TransferService.Create("run.bin", b.length, hash(b), "request_123");
    var first = service.create(c);
    assertEquals(first.id(), service.create(c).id());
    assertEquals(1, store.db().queryForObject("SELECT count(*) FROM transfer", Integer.class));
    assertEquals(37, first.parts().getFirst().size());
    assertEquals("OPEN", first.status());
    error(
        409,
        () ->
            service.create(
                new TransferService.Create("other.bin", b.length, hash(b), "request_123")));
  }

  @Test
  void validatesNameSizeHashAndRequestIdentity() {
    for (String name : List.of("../bad", "bad\\file", "bad\nfile", ""))
      error(
          400,
          () -> service.create(new TransferService.Create(name, 1, "a".repeat(64), "request_123")));
    error(
        413,
        () -> service.create(new TransferService.Create("ok", 0, "a".repeat(64), "request_123")));
    error(
        413,
        () ->
            service.create(
                new TransferService.Create(
                    "ok", config.maxFileSize() + 1, "a".repeat(64), "request_123")));
    error(400, () -> service.create(new TransferService.Create("ok", 1, "wrong", "request_123")));
  }

  @Test
  void fileBoundaryProducesCorrectFinalPart() {
    var t =
        service.create(
            new TransferService.Create(
                "large.bin", config.chunkSize() + 17, "a".repeat(64), "request_large"));
    assertEquals(2, t.partCount());
    assertEquals(config.chunkSize(), t.parts().getFirst().size());
    assertEquals(17, t.parts().getLast().size());
  }

  @Test
  void capacityBoundIsEnforcedWithoutBreakingIdempotency() {
    Transfer first = null;
    for (int i = 0; i < config.maxOpenTransfers(); i++) {
      var t =
          service.create(new TransferService.Create("f" + i, 1, "a".repeat(64), "capacity_" + i));
      if (first == null) first = t;
    }
    assertEquals(config.maxOpenTransfers(), service.list().size());
    error(429, () -> create(new byte[] {1}));
    var old = service.create(new TransferService.Create("f0", 1, "a".repeat(64), "capacity_0"));
    assertEquals(first.id(), old.id());
  }

  @Test
  void requiresAuthAndKeepsHealthMinimal() {
    assertEquals(401, http.getForEntity("/api/transfers", String.class).getStatusCode().value());
    var bad = new HttpHeaders();
    bad.set("X-Admin-Token", "wrong");
    assertEquals(
        401,
        http.exchange("/api/config", HttpMethod.GET, new HttpEntity<>(bad), String.class)
            .getStatusCode()
            .value());
    assertEquals(200, http.getForEntity("/actuator/health", String.class).getStatusCode().value());
    assertEquals(
        401, http.getForEntity("/actuator/prometheus", String.class).getStatusCode().value());
    assertEquals(
        "nosniff",
        http.getForEntity("/", String.class).getHeaders().getFirst("X-Content-Type-Options"));
  }

  @Test
  void incompleteTransferCannotCompleteOrDownload() {
    var t = create(bytes(20));
    assertEquals("PARTS_MISSING", error(409, () -> service.complete(t.id())).code);
    assertEquals("DOWNLOAD_LOCKED", error(409, () -> service.download(t.id())).code);
    assertEquals("OPEN", service.get(t.id()).status());
  }

  @Test
  void checksumMismatchDoesNotCommitProgress() throws Exception {
    byte[] b = bytes(100);
    var t = create(b);
    assertEquals(
        "CHECKSUM_MISMATCH",
        error(
                422,
                () ->
                    service.upload(
                        t.id(), 1, b.length, "0".repeat(64), new ByteArrayInputStream(b)))
            .code);
    var state = service.get(t.id());
    assertEquals(0, state.acceptedBytes());
    assertTrue(state.events().stream().anyMatch(e -> e.type().equals("CHUNK_REJECTED")));
    try (var files = Files.list(DATA.resolve("staging"))) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void exactLengthIsCheckedEvenWhenHeaderLies() {
    var t = create(bytes(10));
    error(
        422,
        () -> service.upload(t.id(), 1, 10, hash(bytes(9)), new ByteArrayInputStream(bytes(9))));
    error(
        422,
        () -> service.upload(t.id(), 1, 10, hash(bytes(11)), new ByteArrayInputStream(bytes(11))));
    assertEquals(0, service.get(t.id()).acceptedBytes());
  }

  @Test
  void rejectsWrongPartNumberMissingLengthAndBadDigest() {
    var t = create(bytes(10));
    error(
        400,
        () -> service.upload(t.id(), 0, 10, hash(bytes(10)), new ByteArrayInputStream(bytes(10))));
    error(
        400,
        () -> service.upload(t.id(), 2, 10, hash(bytes(10)), new ByteArrayInputStream(bytes(10))));
    error(
        411,
        () -> service.upload(t.id(), 1, -1, hash(bytes(10)), new ByteArrayInputStream(bytes(10))));
    error(400, () -> service.upload(t.id(), 1, 10, "bad", new ByteArrayInputStream(bytes(10))));
  }

  @Test
  void duplicateRetryKeepsOneReceipt() throws Exception {
    byte[] b = bytes(63);
    var t = create(b);
    upload(t, 1, b);
    var receipt = service.upload(t.id(), 1, b.length, hash(b), new ByteArrayInputStream(b));
    assertEquals(true, receipt.get("duplicate"));
    assertEquals(
        1,
        service.get(t.id()).events().stream()
            .filter(e -> e.type().equals("CHUNK_ACCEPTED"))
            .count());
    assertEquals(b.length, service.get(t.id()).acceptedBytes());
  }

  @Test
  void conflictingVerifiedPartCannotOverwriteAcceptedData() throws Exception {
    var t = create(bytes(63));
    upload(t, 1, bytes(63));
    byte[] different = new byte[63];
    error(409, () -> upload(t, 1, different));
    assertEquals(hash(bytes(63)), service.get(t.id()).parts().getFirst().sha256());
  }

  @Test
  void concurrentDuplicateUploadCommitsOnePart() throws Exception {
    byte[] b = bytes(1000);
    var t = create(b);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a =
          workers.submit(
              () -> service.upload(t.id(), 1, b.length, hash(b), new ByteArrayInputStream(b)));
      var c =
          workers.submit(
              () -> service.upload(t.id(), 1, b.length, hash(b), new ByteArrayInputStream(b)));
      assertNotEquals(
          a.get(10, TimeUnit.SECONDS).get("duplicate"),
          c.get(10, TimeUnit.SECONDS).get("duplicate"));
    }
    assertEquals(
        1,
        store
            .db()
            .queryForObject(
                "SELECT count(*) FROM part WHERE accepted_at IS NOT NULL", Integer.class));
  }

  @Test
  void outOfOrderPartsAreReassembledInManifestOrder() throws Exception {
    byte[] all = bytes((int) config.chunkSize() + 73);
    var t = create(all);
    byte[] first = Arrays.copyOfRange(all, 0, (int) config.chunkSize()),
        last = Arrays.copyOfRange(all, (int) config.chunkSize(), all.length);
    upload(t, 2, last);
    upload(t, 1, first);
    service.complete(t.id());
    assertTrue(service.verifyOne());
    assertEquals("COMPLETED", service.get(t.id()).status());
    try (var in = service.download(t.id())) {
      assertArrayEquals(all, in.readAllBytes());
    }
  }

  @Test
  void fullFileDigestIsCheckedSeparatelyFromChunkDigests() throws Exception {
    byte[] b = bytes(100);
    var t =
        service.create(
            new TransferService.Create(
                "wrong-original.bin", b.length, "a".repeat(64), "wrong_file_1"));
    upload(t, 1, b);
    service.complete(t.id());
    service.verifyOne();
    assertEquals("FAILED_INTEGRITY", service.get(t.id()).status());
    assertEquals(hash(b), service.get(t.id()).actualSha256());
    error(409, () -> service.download(t.id()));
  }

  @Test
  void storageCorruptionIsDetectedBeforePublication() throws Exception {
    byte[] b = bytes(100);
    var t = create(b);
    upload(t, 1, b);
    String key = service.get(t.id()).parts().getFirst().key();
    Files.write(DATA.resolve("local").resolve(key), new byte[100]);
    service.complete(t.id());
    service.verifyOne();
    assertEquals("FAILED_INTEGRITY", service.get(t.id()).status());
    assertTrue(service.get(t.id()).lastError().contains("STORED_PART_CORRUPT"));
  }

  @Test
  void completedObjectSurvivesStagingCleanup() throws Exception {
    byte[] b = bytes(101);
    var t = create(b);
    upload(t, 1, b);
    service.complete(t.id());
    service.verifyOne();
    assertTrue(service.cleanOne());
    assertTrue(service.get(t.id()).cleaned());
    assertEquals(hash(b), service.get(t.id()).actualSha256());
    try (var in = service.download(t.id())) {
      assertArrayEquals(b, in.readAllBytes());
    }
    assertTrue(service.get(t.id()).parts().getFirst().accepted());
  }

  @Test
  void completeAndCancelAreIdempotentWithinTheirStates() throws Exception {
    var t = create(bytes(8));
    upload(t, 1, bytes(8));
    assertEquals("VERIFYING", service.complete(t.id()).status());
    assertEquals("VERIFYING", service.complete(t.id()).status());
    error(409, () -> service.cancel(t.id()));
    service.verifyOne();
    assertEquals("COMPLETED", service.complete(t.id()).status());
    var other = create(bytes(9));
    service.cancel(other.id());
    assertEquals("CANCELLED", service.cancel(other.id()).status());
    error(409, () -> upload(other, 1, bytes(9)));
  }

  @Test
  void cancellationWinsAgainstAnUncommittedUpload() throws Exception {
    byte[] b = bytes(100);
    var t = create(b);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    InputStream slow =
        new ByteArrayInputStream(b) {
          boolean waited = false;

          public synchronized int read(byte[] out, int off, int len) {
            if (!waited) {
              waited = true;
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new RuntimeException("timeout");
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
            return super.read(out, off, len);
          }
        };
    try (var workers = Executors.newSingleThreadExecutor()) {
      var result = workers.submit(() -> service.upload(t.id(), 1, b.length, hash(b), slow));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      service.cancel(t.id());
      release.countDown();
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
      assertInstanceOf(ApiException.class, e.getCause());
      assertEquals(0, service.get(t.id()).acceptedBytes());
    } finally {
      release.countDown();
    }
  }

  @Test
  void expirationCleansBytesAndPreservesAudit() throws Exception {
    var t = create(bytes(9));
    upload(t, 1, bytes(9));
    store
        .db()
        .update("UPDATE transfer SET expires_at=now()-interval '1 second' WHERE id=?", t.id());
    error(410, () -> upload(t, 1, bytes(9)));
    assertTrue(service.cleanOne());
    assertEquals("EXPIRED", service.get(t.id()).status());
    assertTrue(service.get(t.id()).cleaned());
    assertTrue(service.get(t.id()).events().stream().anyMatch(e -> e.type().equals("EXPIRED")));
  }

  @Test
  void queuedVerificationSurvivesServiceRecreation() throws Exception {
    var t = create(bytes(90));
    upload(t, 1, bytes(90));
    service.complete(t.id());
    var restarted = new TransferService(store, storage, config, tx, metrics);
    assertTrue(restarted.verifyOne());
    assertEquals("COMPLETED", restarted.get(t.id()).status());
  }

  @Test
  void transientStorageFailuresHaveABoundedRetryAndOperatorRecovery() throws Exception {
    var t = create(bytes(90));
    upload(t, 1, bytes(90));
    service.complete(t.id());
    Storage failed =
        new Delegating(storage) {
          public InputStream openPart(String key) throws IOException {
            throw new IOException("injected outage");
          }
        };
    var failing = new TransferService(store, failed, config, tx, metrics);
    for (int n = 0; n < 3; n++) {
      store.db().update("UPDATE transfer SET next_verify_at=now() WHERE id=?", t.id());
      assertTrue(failing.verifyOne());
    }
    assertEquals("VERIFY_FAILED", service.get(t.id()).status());
    assertEquals(3, service.get(t.id()).verificationAttempts());
    service.retryVerification(t.id());
    service.verifyOne();
    assertEquals("COMPLETED", service.get(t.id()).status());
  }

  @Test
  void workerRowLockPreventsASecondFinalizer() throws Exception {
    var t = create(bytes(90));
    upload(t, 1, bytes(90));
    service.complete(t.id());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var once = new AtomicInteger();
    Storage blocking =
        new Delegating(storage) {
          public InputStream openPart(String key) throws IOException {
            if (once.getAndIncrement() == 0) {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("timeout");
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }
            return super.openPart(key);
          }
        };
    var first = new TransferService(store, blocking, config, tx, metrics);
    try (var workers = Executors.newSingleThreadExecutor()) {
      var result = workers.submit(first::verifyOne);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertFalse(service.verifyOne());
      release.countDown();
      assertTrue(result.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  @Test
  void uploadCapacityReturnsRetryableBusy() throws Exception {
    var t = create(bytes(9));
    service.permits.acquire(config.maxConcurrentUploads());
    try {
      assertEquals("UPLOAD_BUSY", error(429, () -> upload(t, 1, bytes(9))).code);
    } finally {
      service.permits.release(config.maxConcurrentUploads());
    }
  }

  @Test
  void downloadApiReturnsVerifiedHashAndExactContent() throws Exception {
    byte[] b = bytes(99);
    var t = create(b);
    upload(t, 1, b);
    service.complete(t.id());
    service.verifyOne();
    var response =
        http.exchange(
            "/api/transfers/" + t.id() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(auth()),
            byte[].class);
    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(b, response.getBody());
    assertEquals(hash(b), response.getHeaders().getFirst("X-File-SHA256"));
    assertEquals("no-store", response.getHeaders().getCacheControl());
  }

  @Test
  void httpUploadAndUsefulErrorContract() throws Exception {
    byte[] b = bytes(103);
    var t = create(b);
    var headers = auth();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.set("X-Chunk-SHA256", hash(b));
    assertEquals(
        200,
        http.exchange(
                "/api/transfers/" + t.id() + "/parts/1",
                HttpMethod.PUT,
                new HttpEntity<>(b, headers),
                String.class)
            .getStatusCode()
            .value());
    var bad =
        http.exchange(
            "/api/transfers/" + t.id() + "/parts/9",
            HttpMethod.PUT,
            new HttpEntity<>(b, headers),
            String.class);
    assertEquals(400, bad.getStatusCode().value());
    assertTrue(bad.getBody().contains("INVALID_REQUEST"));
  }

  @Test
  void changingStorageCannotSilentlyResumeAnOldTransfer() {
    var t = create(bytes(9));
    store.db().update("UPDATE transfer SET storage='s3' WHERE id=?", t.id());
    assertEquals("STORAGE_CHANGED", error(409, () -> service.complete(t.id())).code);
  }

  @Test
  void cancellationReclaimsAnUnpublishedObjectAfterFailedReadback() throws Exception {
    var t = create(bytes(90));
    upload(t, 1, bytes(90));
    service.complete(t.id());
    Storage badRead =
        new Delegating(storage) {
          public InputStream openObject(UUID id) {
            return new ByteArrayInputStream(new byte[90]);
          }
        };
    new TransferService(store, badRead, config, tx, metrics).verifyOne();
    assertEquals("FAILED_INTEGRITY", service.get(t.id()).status());
    assertTrue(Files.exists(DATA.resolve("local/objects/" + t.id())));
    service.cancel(t.id());
    service.cleanOne();
    assertFalse(Files.exists(DATA.resolve("local/objects/" + t.id())));
  }

  @Test
  void stagingSweepOnlyRemovesOldUploadTemporaryFiles() throws Exception {
    Path old = Files.createTempFile(service.staging, "upload-", ".tmp"),
        recent = Files.createTempFile(service.staging, "upload-", ".tmp");
    Files.setLastModifiedTime(
        old,
        java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(49 * 3600)));
    service.sweepStaging();
    assertFalse(Files.exists(old));
    assertTrue(Files.exists(recent));
    Files.delete(recent);
  }

  @AfterAll
  static void closeDatabase() throws Exception {
    PG.close();
  }

  static class Delegating implements Storage {
    final Storage d;

    Delegating(Storage d) {
      this.d = d;
    }

    public String kind() {
      return d.kind();
    }

    public String putPart(UUID id, int number, Path p) throws IOException {
      return d.putPart(id, number, p);
    }

    public InputStream openPart(String key) throws IOException {
      return d.openPart(key);
    }

    public void assemble(UUID id, List<Transfer.Part> p, long size) throws IOException {
      d.assemble(id, p, size);
    }

    public InputStream openObject(UUID id) throws IOException {
      return d.openObject(id);
    }

    public void cleanupParts(UUID id) throws IOException {
      d.cleanupParts(id);
    }

    public void discardObject(UUID id) throws IOException {
      d.discardObject(id);
    }
  }
}
