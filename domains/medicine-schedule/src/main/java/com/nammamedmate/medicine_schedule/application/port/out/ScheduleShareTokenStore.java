package com.nammamedmate.medicine_schedule.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleShareTokenStore {

  ScheduleShareTokenRecord insert(ScheduleShareTokenRecord token);

  Optional<ScheduleShareTokenRecord> findByToken(String token);

  record ScheduleShareTokenRecord(
      UUID id,
      String token,
      UUID customerId,
      UUID memberId,
      Instant expiresAt,
      Instant createdAt) {}
}
