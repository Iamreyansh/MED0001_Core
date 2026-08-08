package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface AdminStaffEmailPort {

  void sendInvite(
      UUID staffId, String email, String name, String plaintextToken, Instant expiresAt);

  void sendPasswordReset(
      UUID staffId, String email, String name, String plaintextToken, Instant expiresAt);
}
