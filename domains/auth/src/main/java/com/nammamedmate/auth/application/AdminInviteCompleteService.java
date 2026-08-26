package com.nammamedmate.auth.application;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consumes an admin invite token and sets the first password (X20). */
@Service
public class AdminInviteCompleteService {

  private static final Pattern PASSWORD_OK = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private DigestFactory digests;

  public AdminInviteCompleteService(
      JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.digests = () -> MessageDigest.getInstance("SHA-256");
  }

  AdminInviteCompleteService withDigests(DigestFactory next) {
    this.digests = next;
    return this;
  }

  @Transactional
  public Map<String, Object> complete(String token, String password) {
    if (token == null || token.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "invite_token is required", 400);
    }
    if (password == null || !PASSWORD_OK.matcher(password).matches()) {
      throw new AppException(
          "VALIDATION_ERROR",
          "password must be at least 8 characters with a letter and a number",
          400);
    }
    String hash = sha256Hex(token.trim());
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT id, email, status, invite_expires_at
            FROM admin_staff
            WHERE invite_token_hash = ? AND deleted_at IS NULL
            """,
            hash);
    if (rows.isEmpty()) {
      throw new AppException("INVITE_INVALID", "Invite token is invalid", 404);
    }
    Map<String, Object> row = rows.getFirst();
    if (!"INVITED".equals(String.valueOf(row.get("status")))) {
      throw new AppException("INVITE_ALREADY_USED", "Invite has already been completed", 409);
    }
    Timestamp expires = (Timestamp) row.get("invite_expires_at");
    Instant now = clock.instant();
    if (expires != null && expires.toInstant().isBefore(now)) {
      throw new AppException("INVITE_EXPIRED", "Invite token has expired", 410);
    }
    UUID id = (UUID) row.get("id");
    int updated =
        jdbc.update(
            """
            UPDATE admin_staff
            SET password_hash = ?, status = 'ACTIVE', invite_token_hash = NULL,
                updated_at = ?
            WHERE id = ? AND status = 'INVITED'
            """,
            passwordEncoder.encode(password),
            Timestamp.from(now),
            id);
    if (updated != 1) {
      throw new AppException("INVITE_ALREADY_USED", "Invite has already been completed", 409);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("admin_id", id);
    data.put("email", row.get("email"));
    data.put("status", "ACTIVE");
    data.put("mfa_setup_required", true);
    return data;
  }

  String sha256Hex(String value) {
    try {
      MessageDigest digest = digests.create();
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
