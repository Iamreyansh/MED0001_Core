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

/** Consumes an admin password-reset token (R21). */
@Service
public class AdminPasswordResetCompleteService {

  private static final Pattern PASSWORD_OK = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private DigestFactory digests;

  public AdminPasswordResetCompleteService(
      JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.digests = () -> MessageDigest.getInstance("SHA-256");
  }

  AdminPasswordResetCompleteService withDigests(DigestFactory next) {
    this.digests = next;
    return this;
  }

  @Transactional
  public Map<String, Object> complete(String token, String password) {
    if (token == null || token.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reset_token is required", 400);
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
            SELECT id, email, reset_token_expires_at
            FROM admin_staff
            WHERE reset_token_hash = ? AND deleted_at IS NULL
            """,
            hash);
    if (rows.isEmpty()) {
      throw new AppException("RESET_INVALID", "Reset token is invalid", 404);
    }
    Map<String, Object> row = rows.getFirst();
    Timestamp expires = (Timestamp) row.get("reset_token_expires_at");
    Instant now = clock.instant();
    if (expires != null && expires.toInstant().isBefore(now)) {
      throw new AppException("RESET_EXPIRED", "Reset token has expired", 410);
    }
    UUID id = (UUID) row.get("id");
    int updated =
        jdbc.update(
            """
            UPDATE admin_staff
            SET password_hash = ?, reset_token_hash = NULL, reset_token_expires_at = NULL,
                updated_at = ?
            WHERE id = ? AND reset_token_hash = ?
            """,
            passwordEncoder.encode(password),
            Timestamp.from(now),
            id,
            hash);
    if (updated != 1) {
      throw new AppException("RESET_INVALID", "Reset token is invalid", 404);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("admin_id", id);
    data.put("email", row.get("email"));
    data.put("status", "ACTIVE");
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
