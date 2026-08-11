package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record DeviceToken(
    UUID id,
    UUID userId,
    NotificationUserType userType,
    String token,
    DevicePlatform platform,
    String deviceId,
    boolean active,
    Instant registeredAt,
    Instant lastRefreshedAt) {}
