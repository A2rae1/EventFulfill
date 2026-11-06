package cn.hm.event.domain;

import java.time.LocalDateTime;

public final class Rows {
    private Rows() {}
    public record Activity(String id, String ownerId, String title, String description, LocalDateTime createdAt) {}
    public record Session(String id, String activityId, String ownerId, String title, int capacity,
                          int used, int priceCents, int holdSeconds, boolean published, long version) {}
    public record Booking(long seq, String id, String sessionId, String userId, String idemKey,
                          String status, String orderId, LocalDateTime createdAt) {}
    public record Order(String id, String requestId, String sessionId, String userId,
                        String status, String kind, int priceCents, LocalDateTime expiresAt, long version) {}
    public record WaitEntry(String id, long seq, String requestId, String sessionId, String userId, String status) {}
    public record Ticket(String id, String orderId, String userId, String token, String status, LocalDateTime checkedAt) {}
    public record User(String id, String passwordHash, String role) {}
    public record Outbox(String id, String topic, String aggregateId, String payload, String status,
                         int attempts, LocalDateTime availableAt, LocalDateTime createdAt,
                         String lastError, LocalDateTime leaseUntil, String leaseToken) {}
}
