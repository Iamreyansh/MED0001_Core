package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface PharmacySessionStore {

  void save(
      UUID sessionId,
      UUID userId,
      String refreshTokenHash,
      String clientIp,
      String userAgent,
      Instant now,
      Instant expiresAt,
      UUID pharmacyId);
}
