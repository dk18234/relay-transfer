package dev.dineshrama.relay;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {
  final TransferStore store;
  final Storage storage;
  final RelayProperties config;
  final TransactionTemplate tx;
  final Semaphore permits;
  final Path staging;
  final MeterRegistry metrics;

  public record Create(String name, long size, String sha256, String requestKey) {}

  public TransferService(
      TransferStore s,
      Storage storage,
      RelayProperties config,
      TransactionTemplate tx,
      MeterRegistry metrics)
      throws IOException {
    this.store = s;
    this.storage = storage;
    this.config = config;
    this.tx = tx;
    this.metrics = metrics;
    permits = new Semaphore(config.maxConcurrentUploads(), true);
    staging = config.dataDirectory().resolve("staging").toAbsolutePath();
    Files.createDirectories(staging);
    metrics.gauge(
        "relay.uploads.active", permits, p -> config.maxConcurrentUploads() - p.availablePermits());
  }

  public Transfer create(Create c) {
    if (c == null
        || c.name() == null
        || c.name().isBlank()
        || c.name().length() > 200
        || c.name().chars().anyMatch(ch -> ch < 32 || ch == 127)
        || c.name().contains("/")
        || c.name().contains("\\"))
      throw ApiException.bad("Use a plain file name of 1–200 printable characters");
    if (c.size() < 1 || c.size() > config.maxFileSize())
      throw new ApiException(413, "FILE_LIMIT", "File size exceeds the configured limit");
    if (!Hashes.valid(c.sha256())
        || c.requestKey() == null
        || !c.requestKey().matches("[A-Za-z0-9_-]{8,100}"))
      throw ApiException.bad("Provide a SHA-256 digest and an 8–100 character request key");
    return tx.execute(
        status -> {
          // One bounded creation lock protects capacity and idempotency, not data transfer.
          store.db().execute("SELECT pg_advisory_xact_lock(733102)");
          UUID existing = store.byRequest(c.requestKey());
          if (existing != null) {
            var t = store.get(existing, "");
            if (t.name().equals(c.name())
                && t.size() == c.size()
                && t.expectedSha256().equalsIgnoreCase(c.sha256())) return t;
            throw ApiException.conflict(
                "REQUEST_KEY_CONFLICT", "Request key belongs to a different file");
          }
          if (store
                  .db()
                  .queryForObject(
                      "SELECT count(*) FROM transfer WHERE status IN ('OPEN','VERIFYING')",
                      Long.class)
              >= config.maxOpenTransfers())
            throw new ApiException(
                429,
                "CAPACITY_LIMIT",
                "Too many open transfers; finish or cancel an existing session");
          UUID id = UUID.randomUUID();
          int count = (int) ((c.size() + config.chunkSize() - 1) / config.chunkSize());
          store
              .db()
              .update(
                  "INSERT INTO"
                      + " transfer(id,request_key,name,size,chunk_size,part_count,expected_sha256,status,storage,expires_at)"
                      + " VALUES(?,?,?,?,?,?,?,'OPEN',?,?)",
                  id,
                  c.requestKey(),
                  c.name(),
                  c.size(),
                  config.chunkSize(),
                  count,
                  c.sha256().toLowerCase(Locale.ROOT),
                  storage.kind(),
                  Timestamp.from(Instant.now().plusSeconds(config.expiryHours() * 3600)));
          for (int n = 1; n <= count; n++)
            store
                .db()
                .update(
                    "INSERT INTO part(transfer_id,number,size) VALUES(?,?,?)",
                    id,
                    n,
                    Math.min(config.chunkSize(), c.size() - (n - 1) * config.chunkSize()));
          store.event(
              id,
              "CREATED",
              "Manifest persisted. Only verified chunks count toward progress.",
              null);
          metrics.counter("relay.transfers.created").increment();
          return store.get(id, "");
        });
  }

  public Transfer get(UUID id) {
    return tx.execute(status -> store.get(id, "FOR SHARE"));
  }

  public List<Map<String, Object>> list() {
    return store.list();
  }

  private void correctStorage(Transfer t) {
    if (!t.storage().equals(storage.kind()))
      throw ApiException.conflict(
          "STORAGE_CHANGED", "Run this transfer with its original storage adapter");
  }

  private void open(Transfer t) {
    correctStorage(t);
    if (!t.status().equals("OPEN"))
      throw ApiException.conflict("NOT_OPEN", "Transfer is " + t.status());
    if (!t.expiresAt().isAfter(Instant.now()))
      throw new ApiException(410, "EXPIRED", "Transfer has expired");
  }

  public Map<String, Object> upload(
      UUID id, int number, long length, String supplied, InputStream input) throws IOException {
    if (!permits.tryAcquire())
      throw new ApiException(429, "UPLOAD_BUSY", "All upload slots are busy; retry with backoff");
    Path temp = null;
    try {
      var initial = get(id);
      open(initial);
      if (number < 1 || number > initial.partCount())
        throw ApiException.bad("Part number is outside the manifest");
      long expected = initial.parts().get(number - 1).size();
      if (length < 0)
        throw new ApiException(411, "LENGTH_REQUIRED", "Content-Length is required for a chunk");
      if (length != expected)
        throw new ApiException(422, "LENGTH_MISMATCH", "Chunk length must match the manifest");
      if (!Hashes.valid(supplied))
        throw ApiException.bad("X-Chunk-SHA256 must be a hex SHA-256 digest");
      temp = Files.createTempFile(staging, "upload-", ".tmp");
      String actual;
      try (OutputStream out = Files.newOutputStream(temp)) {
        actual = Hashes.copyExact(input, out, expected, null);
      }
      if (!actual.equalsIgnoreCase(supplied)) {
        tx.executeWithoutResult(
            status ->
                store.event(
                    id,
                    "CHUNK_REJECTED",
                    "Incoming bytes do not match the supplied SHA-256 digest.",
                    number));
        metrics.counter("relay.chunks.rejected", "reason", "checksum").increment();
        throw new ApiException(
            422,
            "CHECKSUM_MISMATCH",
            "Chunk failed SHA-256 verification; no progress was committed");
      }
      Hashes.sync(temp);
      final Path staged = temp;
      return tx.execute(
          status -> {
            var t = store.get(id, "FOR SHARE");
            open(t);
            // Different part rows can proceed concurrently. Complete/cancel need the parent write
            // lock.
            store
                .db()
                .queryForObject(
                    "SELECT number FROM part WHERE transfer_id=? AND number=? FOR UPDATE",
                    Integer.class,
                    id,
                    number);
            var current =
                store
                    .db()
                    .queryForMap("SELECT * FROM part WHERE transfer_id=? AND number=?", id, number);
            if (current.get("accepted_at") != null) {
              if (actual.equals(current.get("sha256"))) {
                metrics.counter("relay.chunks.duplicates").increment();
                return Map.of("number", number, "sha256", actual, "duplicate", true);
              }
              throw ApiException.conflict(
                  "PART_CONFLICT", "A different verified chunk already occupies this part");
            }
            try {
              String key = storage.putPart(id, number, staged);
              store
                  .db()
                  .update(
                      "UPDATE part SET sha256=?,storage_key=?,accepted_at=now() WHERE transfer_id=?"
                          + " AND number=?",
                      actual,
                      key,
                      id,
                      number);
              store.event(
                  id, "CHUNK_ACCEPTED", "SHA-256 verified; storage and receipt persisted.", number);
              metrics.counter("relay.chunks.accepted").increment();
              metrics.counter("relay.bytes.accepted").increment(expected);
              return Map.of("number", number, "sha256", actual, "duplicate", false);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } finally {
      try {
        if (temp != null) Files.deleteIfExists(temp);
      } finally {
        permits.release();
      }
    }
  }

  public Transfer complete(UUID id) {
    return tx.execute(
        status -> {
          var t = store.get(id, "FOR UPDATE");
          correctStorage(t);
          if (t.status().equals("COMPLETED") || t.status().equals("VERIFYING")) return t;
          open(t);
          if (t.parts().stream().anyMatch(p -> !p.accepted()))
            throw ApiException.conflict(
                "PARTS_MISSING",
                "Every manifest part must have a verified receipt before finalization");
          store
              .db()
              .update(
                  "UPDATE transfer SET"
                      + " status='VERIFYING',updated_at=now(),next_verify_at=now(),last_error=NULL"
                      + " WHERE id=?",
                  id);
          store.event(
              id,
              "VERIFY_QUEUED",
              "Complete-file verification queued durably; download remains locked.",
              null);
          return store.get(id, "");
        });
  }

  public Transfer retryVerification(UUID id) {
    return tx.execute(
        status -> {
          var t = store.get(id, "FOR UPDATE");
          correctStorage(t);
          if (!t.status().equals("VERIFY_FAILED"))
            throw ApiException.conflict(
                "NOT_RETRYABLE", "Only transient verification failures can be retried");
          store
              .db()
              .update(
                  "UPDATE transfer SET"
                      + " status='VERIFYING',verification_attempts=0,next_verify_at=now(),updated_at=now(),last_error=NULL"
                      + " WHERE id=?",
                  id);
          store.event(
              id, "VERIFY_RETRY", "Operator requested a fresh bounded verification attempt.", null);
          return store.get(id, "");
        });
  }

  public Transfer cancel(UUID id) {
    return tx.execute(
        status -> {
          var t = store.get(id, "FOR UPDATE");
          correctStorage(t);
          if (t.status().equals("CANCELLED")) return t;
          if (!Set.of("OPEN", "VERIFY_FAILED", "FAILED_INTEGRITY").contains(t.status()))
            throw ApiException.conflict(
                "CANNOT_CANCEL", "Only open or failed transfers can be cancelled");
          store
              .db()
              .update("UPDATE transfer SET status='CANCELLED',updated_at=now() WHERE id=?", id);
          store.event(
              id, "CANCELLED", "Session closed. Staged chunks are queued for cleanup.", null);
          return store.get(id, "");
        });
  }

  public InputStream download(UUID id) throws IOException {
    var t = get(id);
    correctStorage(t);
    if (!t.status().equals("COMPLETED"))
      throw ApiException.conflict(
          "DOWNLOAD_LOCKED", "File has not passed complete SHA-256 verification");
    return storage.openObject(id);
  }

  /**
   * A PostgreSQL row lock is the worker's ownership fence. Process death releases it and rolls back
   * to VERIFYING.
   */
  public boolean verifyOne() {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var ids =
                  store
                      .db()
                      .query(
                          "SELECT id FROM transfer WHERE status='VERIFYING' AND storage=? AND"
                              + " next_verify_at<=now() ORDER BY updated_at FOR UPDATE SKIP LOCKED"
                              + " LIMIT 1",
                          (r, n) -> r.getObject(1, UUID.class),
                          storage.kind());
              if (ids.isEmpty()) return false;
              UUID id = ids.getFirst();
              var t = store.get(id, "");
              int attempt = t.verificationAttempts() + 1;
              store
                  .db()
                  .update(
                      "UPDATE transfer SET verification_attempts=?,updated_at=now() WHERE id=?",
                      attempt,
                      id);
              try {
                MessageDigest full = Hashes.sha256();
                for (var part : t.parts()) {
                  try (InputStream in = storage.openPart(part.key())) {
                    String sha = Hashes.copyExact(in, null, part.size(), full);
                    if (!sha.equals(part.sha256()))
                      throw new ApiException(
                          422,
                          "STORED_PART_CORRUPT",
                          "Stored part " + part.number() + " failed integrity verification");
                  }
                }
                String actual = Hashes.hex(full);
                if (!actual.equals(t.expectedSha256())) {
                  integrityFailure(
                      id,
                      actual,
                      "FULL_CHECKSUM_MISMATCH",
                      "All chunks arrived, but their combined SHA-256 differs from the declared"
                          + " file.");
                  return true;
                }
                storage.assemble(id, t.parts(), t.size());
                // Read the assembled object again, independent of adapter metadata or S3 ETags.
                String objectSha;
                try (InputStream in = storage.openObject(id)) {
                  objectSha = Hashes.copyExact(in, null, t.size(), null);
                }
                if (!objectSha.equals(t.expectedSha256())) {
                  integrityFailure(
                      id,
                      objectSha,
                      "OBJECT_CHECKSUM_MISMATCH",
                      "Assembled object does not match the declared SHA-256.");
                  return true;
                }
                store
                    .db()
                    .update(
                        "UPDATE transfer SET"
                            + " status='COMPLETED',actual_sha256=?,last_error=NULL,updated_at=now()"
                            + " WHERE id=?",
                        objectSha,
                        id);
                store.event(
                    id,
                    "VERIFIED",
                    "Assembled object independently re-read. Full SHA-256 matches the original"
                        + " manifest.",
                    null);
                metrics.counter("relay.transfers.verified").increment();
                return true;
              } catch (ApiException e) {
                integrityFailure(id, null, e.code, e.getMessage());
                return true;
              } catch (IOException | RuntimeException e) {
                String message =
                    "Storage operation failed; "
                        + (attempt < 3
                            ? "verification will retry with backoff."
                            : "three attempts exhausted; operator retry available.");
                store
                    .db()
                    .update(
                        "UPDATE transfer SET status=?,last_error=?,next_verify_at=now()+(? *"
                            + " interval '1 second'),updated_at=now() WHERE id=?",
                        attempt < 3 ? "VERIFYING" : "VERIFY_FAILED",
                        message,
                        5 * attempt,
                        id);
                store.event(id, "STORAGE_RETRY", message, null);
                metrics.counter("relay.storage.failures").increment();
                return true;
              }
            }));
  }

  private void integrityFailure(UUID id, String actual, String code, String message) {
    store
        .db()
        .update(
            "UPDATE transfer SET"
                + " status='FAILED_INTEGRITY',actual_sha256=?,last_error=?,updated_at=now() WHERE"
                + " id=?",
            actual,
            code + ": " + message,
            id);
    store.event(id, "INTEGRITY_FAILED", code + ": " + message, null);
    metrics.counter("relay.transfers.integrity_failed").increment();
  }

  public boolean cleanOne() {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var ids =
                  store
                      .db()
                      .query(
                          "SELECT id FROM transfer WHERE storage=? AND ((status='OPEN' AND"
                              + " expires_at<=now()) OR (status IN"
                              + " ('COMPLETED','CANCELLED','EXPIRED') AND NOT cleaned)) ORDER BY"
                              + " updated_at FOR UPDATE SKIP LOCKED LIMIT 1",
                          (r, n) -> r.getObject(1, UUID.class),
                          storage.kind());
              if (ids.isEmpty()) return false;
              UUID id = ids.getFirst();
              var t = store.get(id, "");
              if (t.status().equals("OPEN")) {
                store
                    .db()
                    .update("UPDATE transfer SET status='EXPIRED',updated_at=now() WHERE id=?", id);
                store.event(
                    id,
                    "EXPIRED",
                    "Session expired without finalization; staged chunks will be removed.",
                    null);
              }
              try {
                storage.cleanupParts(id);
                if (!t.status().equals("COMPLETED")) storage.discardObject(id);
                store.db().update("UPDATE transfer SET cleaned=true WHERE id=?", id);
                store.event(
                    id, "CLEANED", "Staged chunk bytes removed; audit receipts retained.", null);
              } catch (IOException | RuntimeException e) {
                return false;
              }
              return true;
            }));
  }

  public void sweepStaging() throws IOException {
    Instant cutoff = Instant.now().minusSeconds(48 * 3600);
    try (var files = Files.list(staging)) {
      for (Path file :
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith("upload-")
                          && p.getFileName().toString().endsWith(".tmp"))
              .toList())
        if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff))
          Files.deleteIfExists(file);
    }
  }
}
