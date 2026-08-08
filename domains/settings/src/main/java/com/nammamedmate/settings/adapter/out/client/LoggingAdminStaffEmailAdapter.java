package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.settings.application.port.out.AdminStaffEmailPort;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logging stub — no real SMTP (EPIC-021 STORY-001). */
@Component
public class LoggingAdminStaffEmailAdapter implements AdminStaffEmailPort {

  private static final Logger log = LoggerFactory.getLogger(LoggingAdminStaffEmailAdapter.class);

  @Override
  public void sendInvite(
      UUID staffId, String email, String name, String plaintextToken, Instant expiresAt) {
    log.info(
        "admin_invite_email staffId={} tokenLen={} expiresAt={}",
        staffId,
        plaintextToken == null ? 0 : plaintextToken.length(),
        expiresAt);
  }

  @Override
  public void sendPasswordReset(
      UUID staffId, String email, String name, String plaintextToken, Instant expiresAt) {
    log.info(
        "admin_password_reset_email staffId={} tokenLen={} expiresAt={}",
        staffId,
        plaintextToken == null ? 0 : plaintextToken.length(),
        expiresAt);
  }
}
