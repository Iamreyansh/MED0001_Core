package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Auth-side rider directory (JDBC). No compile dep on domains/rider. */
public interface RiderAccountPort {

  record RiderAccount(
      UUID id,
      String phone,
      String name,
      String status,
      String kycStatus,
      String email,
      String kycRejectionReason,
      String kycRejectionNotes) {}

  Optional<RiderAccount> findByPhone(String phone);

  Optional<RiderAccount> findById(UUID id);
}
