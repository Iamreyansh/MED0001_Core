package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface RegistrationAuditStore {

  void save(
      UUID id,
      UUID pharmacyId,
      String email,
      String phone,
      String ip,
      String outcome,
      String errorCode,
      Instant at);
}
