package dev.dineshrama.relay;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Transfer(UUID id, String name, long size, long chunkSize, int partCount, String expectedSha256,
        String status, String storage, Instant createdAt, Instant updatedAt, Instant expiresAt,
        int verificationAttempts, String actualSha256, String lastError, boolean cleaned,
        List<Part> parts, List<Event> events) {
    public long acceptedBytes() { return parts.stream().filter(Part::accepted).mapToLong(Part::size).sum(); }
    public record Part(int number, long size, String sha256, String key, Instant acceptedAt) {
        public boolean accepted() { return acceptedAt != null; }
    }
    public record Event(long sequence, String type, String message, Integer part, Instant at) {}
}
