package dev.dineshrama.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  final TransferService service;
  final RelayProperties config;

  public ApiController(TransferService s, RelayProperties p) {
    service = s;
    config = p;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    return Map.of(
        "mode",
        "live",
        "storage",
        config.storage(),
        "chunkSize",
        config.chunkSize(),
        "maxFileSize",
        config.maxFileSize(),
        "maxConcurrentUploads",
        config.maxConcurrentUploads(),
        "expiryHours",
        config.expiryHours());
  }

  @GetMapping("/transfers")
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public Transfer create(@RequestBody TransferService.Create c) {
    return service.create(c);
  }

  @GetMapping("/transfers/{id}")
  public Transfer get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PutMapping("/transfers/{id}/parts/{number}")
  public Map<String, Object> upload(
      @PathVariable UUID id,
      @PathVariable int number,
      @RequestHeader(value = "X-Chunk-SHA256", required = false) String hash,
      HttpServletRequest request)
      throws IOException {
    return service.upload(
        id, number, request.getContentLengthLong(), hash, request.getInputStream());
  }

  @PostMapping("/transfers/{id}/complete")
  public Transfer complete(@PathVariable UUID id) {
    return service.complete(id);
  }

  @PostMapping("/transfers/{id}/retry-verification")
  public Transfer retry(@PathVariable UUID id) {
    return service.retryVerification(id);
  }

  @PostMapping("/transfers/{id}/cancel")
  public Transfer cancel(@PathVariable UUID id) {
    return service.cancel(id);
  }

  @GetMapping("/transfers/{id}/download")
  public void download(@PathVariable UUID id, HttpServletResponse response) throws IOException {
    var t = service.get(id);
    try (InputStream in = service.download(id)) {
      response.setContentType("application/octet-stream");
      response.setContentLengthLong(t.size());
      response.setHeader(
          "Content-Disposition",
          ContentDisposition.attachment()
              .filename(t.name(), StandardCharsets.UTF_8)
              .build()
              .toString());
      response.setHeader("X-File-SHA256", t.actualSha256());
      in.transferTo(response.getOutputStream());
    }
  }
}
