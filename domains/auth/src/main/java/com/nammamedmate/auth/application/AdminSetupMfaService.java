package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.domain.BackupCodes;
import com.nammamedmate.auth.domain.Base32;
import com.nammamedmate.auth.domain.Totp;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSetupMfaService {

  static final int SETUP_RATE_LIMIT = 5;
  static final int SETUP_RATE_WINDOW_SECONDS = 3600;
  static final int SECRET_BYTES = 20;
  private static final String ISSUER = "NammaMedMate";

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final AdminStaffStore staffStore;
  private final AesGcmCipher aesGcmCipher;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final DigestFactory digestFactory;

  @Autowired
  public AdminSetupMfaService(
      AdminStaffStore staffStore, AesGcmCipher aesGcmCipher, RateLimiter rateLimiter, Clock clock) {
    this(
        staffStore,
        aesGcmCipher,
        rateLimiter,
        clock,
        new SecureRandom(),
        () -> MessageDigest.getInstance("SHA-256"));
  }

  AdminSetupMfaService(
      AdminStaffStore staffStore,
      AesGcmCipher aesGcmCipher,
      RateLimiter rateLimiter,
      Clock clock,
      SecureRandom secureRandom,
      DigestFactory digestFactory) {
    this.staffStore = staffStore;
    this.aesGcmCipher = aesGcmCipher;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.digestFactory = digestFactory;
  }

  public AdminSetupMfaResult setup(UUID adminId) {
    if (adminId == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }

    String rateKey = "admin:user:setup-mfa:" + adminId + ":count";
    if (!rateLimiter.tryAcquire(rateKey, SETUP_RATE_LIMIT, SETUP_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "MFA setup rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(rateKey, SETUP_RATE_LIMIT, SETUP_RATE_WINDOW_SECONDS));
    }

    AdminStaffRecord admin =
        staffStore
            .findById(adminId)
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "Authentication required", 401));

    try {
      AuthRole.fromValue(admin.role());
    } catch (IllegalArgumentException ex) {
      throw new AppException("FORBIDDEN", "Role not permitted", 403);
    }

    if (admin.mfaEnabled()) {
      throw new AppException("MFA_ALREADY_ENROLLED", "MFA is already enrolled", 400);
    }

    byte[] secretBytes = new byte[SECRET_BYTES];
    secureRandom.nextBytes(secretBytes);
    String totpSecret = Base32.encode(secretBytes);
    List<String> plainBackup = BackupCodes.generate(secureRandom);
    List<Map<String, Object>> stored = BackupCodes.toStoredRows(plainBackup, this::sha256Hex);

    Instant now = clock.instant();
    AdminStaffRecord updated =
        AdminLoginService.copy(
            admin,
            false,
            aesGcmCipher.encrypt(totpSecret),
            stored,
            admin.failedLoginAttempts(),
            admin.lockedUntil(),
            admin.lastFailedAt(),
            admin.lastLoginAt(),
            admin.lastActiveAt(),
            now);
    staffStore.save(updated);

    // Client renders QR from totp_uri — never send secret to a third-party QR host.
    return new AdminSetupMfaResult(buildUri(admin.email(), totpSecret), totpSecret, plainBackup);
  }

  static String buildUri(String email, String secret) {
    String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8);
    return "otpauth://totp/"
        + label
        + "?secret="
        + secret
        + "&issuer="
        + URLEncoder.encode(ISSUER, StandardCharsets.UTF_8)
        + "&algorithm=SHA1&digits="
        + Totp.DIGITS
        + "&period="
        + Totp.PERIOD_SECONDS;
  }

  String sha256Hex(String value) {
    try {
      MessageDigest digest = digestFactory.create();
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
