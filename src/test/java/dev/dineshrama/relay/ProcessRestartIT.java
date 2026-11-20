package dev.dineshrama.relay;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Runs the packaged application as a separate JVM. It is killed forcibly twice. */
class ProcessRestartIT {
  final ObjectMapper json = new ObjectMapper();
  final String token = "relay-process-restart-fixture-token";
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  String base;
  Process app;
  long rssKiB;

  @Test
  void resumesAfterProcessDeathAndVerifiesAFileLargerThanTheHeap() throws Exception {
    exercise("local", 128L * 1024 * 1024);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "S3_ENDPOINT", matches = ".+")
  void s3AdapterSurvivesRestartAndUsesActualMultipartCopy() throws Exception {
    exercise("s3", 24L * 1024 * 1024);
  }

  void exercise(String storage, long size) throws Exception {
    long started = System.nanoTime();
    Path work = Files.createTempDirectory("relay-process-"),
        source = work.resolve("source.bin"),
        log = work.resolve("application.log");
    try (EmbeddedPostgres pg =
        EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").start()) {
      int port;
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      base = "http://127.0.0.1:" + port;
      String bucket = "relay-it-" + UUID.randomUUID().toString();
      if (storage.equals("s3"))
        try (S3Client s3 =
            S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .endpointOverride(URI.create(System.getenv("S3_ENDPOINT")))
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
          s3.createBucket(b -> b.bucket(bucket));
        }
      MessageDigest sha = Hashes.sha256();
      byte[] block = new byte[64 * 1024];
      new Random(2026).nextBytes(block);
      try (OutputStream out = Files.newOutputStream(source)) {
        for (long written = 0; written < size; ) {
          int n = (int) Math.min(block.length, size - written);
          out.write(block, 0, n);
          sha.update(block, 0, n);
          written += n;
        }
      }
      String expected = Hashes.hex(sha);
      start(pg, port, work, log, storage, bucket, true);
      var t =
          request(
              "/transfers",
              "POST",
              json.writeValueAsBytes(
                  Map.of(
                      "name",
                      "synthetic-research.bin",
                      "size",
                      size,
                      "sha256",
                      expected,
                      "requestKey",
                      "process_" + storage)),
              null,
              201);
      String id = t.get("id").asText();
      long chunk = t.get("chunkSize").asLong();
      int count = t.get("partCount").asInt();
      for (int n = 1; n <= 2; n++) send(source, id, n, chunk, size, false);
      assertEquals(2, accepted(request("/transfers/" + id, "GET", null, null, 200)));
      kill();
      start(pg, port, work, log, storage, bucket, true);
      assertEquals(
          2,
          accepted(request("/transfers/" + id, "GET", null, null, 200)),
          "durable receipts must survive SIGKILL");
      send(source, id, 3, chunk, size, true);
      assertEquals(
          2,
          accepted(request("/transfers/" + id, "GET", null, null, 200)),
          "corrupt bytes must not advance progress");
      for (int n = 3; n <= count; n++) send(source, id, n, chunk, size, false);
      assertEquals(
          "VERIFYING",
          request("/transfers/" + id + "/complete", "POST", null, null, 200)
              .get("status")
              .asText());
      kill();
      start(pg, port, work, log, storage, bucket, false);
      long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
      do {
        t = request("/transfers/" + id, "GET", null, null, 200);
        if (t.get("status").asText().equals("COMPLETED")) break;
        Thread.sleep(150);
      } while (System.nanoTime() < deadline);
      assertEquals("COMPLETED", t.get("status").asText(), t.toString());
      assertEquals(expected, t.get("actualSha256").asText());
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/transfers/" + id + "/download"))
                  .header("X-Admin-Token", token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      String downloaded;
      try (InputStream in = response.body()) {
        downloaded = Hashes.copyExact(in, null, size, null);
      }
      assertEquals(expected, downloaded);
      sampleRss();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("storage", storage);
      report.put("completedAt", Instant.now().toString());
      report.put("sourceBytes", size);
      report.put("serverMaxHeapBytes", 96L * 1024 * 1024);
      report.put("observedPeakRssKiB", rssKiB);
      report.put("chunkSize", chunk);
      report.put("parts", count);
      report.put("acceptedBeforeRestart", 2);
      report.put("restartedProcesses", 2);
      report.put("corruptPartRejected", true);
      report.put("missingChunksUploadedAfterRestart", count - 2);
      report.put("fullSha256", expected);
      report.put("downloadSha256", downloaded);
      report.put("elapsedSeconds", Math.round((System.nanoTime() - started) / 1e7) / 100.0);
      report.put(
          "s3Scope",
          storage.equals("s3") ? "Moto S3 API emulator; not an AWS benchmark" : "not used");
      Path evidence = Path.of("target/evidence");
      Files.createDirectories(evidence);
      json.writerWithDefaultPrettyPrinter()
          .writeValue(evidence.resolve("restart-" + storage + ".json").toFile(), report);
      System.out.println("RELAY_RESTART_EVIDENCE " + json.writeValueAsString(report));
    } finally {
      kill();
    }
  }

  int accepted(JsonNode t) {
    int n = 0;
    for (JsonNode p : t.get("parts")) if (p.hasNonNull("acceptedAt")) n++;
    return n;
  }

  void start(
      EmbeddedPostgres pg,
      int port,
      Path work,
      Path log,
      String storage,
      String bucket,
      boolean delay)
      throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx96m",
                "-jar",
                Path.of("target/relay-transfer-1.0.0.jar").toAbsolutePath().toString(),
                "--server.port=" + port,
                "--relay.worker-initial-delay-ms=" + (delay ? 99999999 : 0),
                "--relay.worker-delay-ms=200"));
    ProcessBuilder b =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    var env = b.environment();
    env.put("ADMIN_TOKEN", token);
    env.put("DATABASE_URL", pg.getJdbcUrl("postgres", "postgres"));
    env.put("DATABASE_USER", "postgres");
    env.put("DATABASE_PASSWORD", "");
    env.put("DATA_DIRECTORY", work.resolve("data").toString());
    env.put("STORAGE", storage);
    env.put("S3_BUCKET", bucket);
    env.put("AWS_ACCESS_KEY_ID", "test");
    env.put("AWS_SECRET_ACCESS_KEY", "test");
    app = b.start();
    for (int i = 0; i < 180; i++) {
      if (!app.isAlive()) fail("Packaged application exited: " + Files.readString(log));
      try {
        var r =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) {
          sampleRss();
          return;
        }
      } catch (IOException ignored) {
      }
      Thread.sleep(100);
    }
    fail("Application did not become ready: " + Files.readString(log));
  }

  void kill() throws InterruptedException {
    if (app != null && app.isAlive()) {
      sampleRss();
      app.destroyForcibly();
      assertTrue(app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  void sampleRss() {
    if (app == null) return;
    try {
      for (String line : Files.readAllLines(Path.of("/proc", Long.toString(app.pid()), "status")))
        if (line.startsWith("VmHWM:"))
          rssKiB = Math.max(rssKiB, Long.parseLong(line.replaceAll("[^0-9]", "")));
    } catch (Exception ignored) {
    }
  }

  JsonNode request(String path, String method, byte[] body, String chunkHash, int status)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(base + "/api" + path))
            .timeout(Duration.ofSeconds(120))
            .header("X-Admin-Token", token);
    if (chunkHash != null)
      b.header("X-Chunk-SHA256", chunkHash).header("Content-Type", "application/octet-stream");
    else if (body != null) b.header("Content-Type", "application/json");
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(status, r.statusCode(), r.body());
    sampleRss();
    return json.readTree(r.body());
  }

  void send(Path source, String id, int number, long chunk, long size, boolean corrupt)
      throws Exception {
    byte[] bytes = new byte[(int) Math.min(chunk, size - (number - 1) * chunk)];
    try (var file = new RandomAccessFile(source.toFile(), "r")) {
      file.seek((number - 1) * chunk);
      file.readFully(bytes);
    }
    String sha;
    var digest = Hashes.sha256();
    digest.update(bytes);
    sha = Hashes.hex(digest);
    if (corrupt) bytes[31] ^= 255;
    request("/transfers/" + id + "/parts/" + number, "PUT", bytes, sha, corrupt ? 422 : 200);
  }
}
