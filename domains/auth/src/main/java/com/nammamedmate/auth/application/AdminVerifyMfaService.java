package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminAuthEventStore;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.domain.BackupCodes;
import com.nammamedmate.auth.domain.Base32;
import com.nammamedmate.auth.domain.Totp;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenRevocationStore;
import com.nammamedmate.security.TokenScope;
import io.jsonwebtoken.ExpiredJwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminVerifyMfaService {

  static final int MFA_RATE_LIMIT = 10;
  static final int MFA_RATE_WINDOW_SECONDS = 60;

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final AdminStaffStore staffStore;
  private final AdminAuthEventStore eventStore;
  private final AdminLoginService loginService;
  private final Rs256JwtService jwtService;
  private final TokenRevocationStore revocationStore;
  private final AesGcmCipher aesGcmCipher;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final DigestFactory digestFactory;

  @Autowired
  public AdminVerifyMfaService(
      AdminStaffStore staffStore,
      AdminAuthEventStore eventStore,
      AdminLoginService loginService,
      Rs256JwtService jwtService,
      TokenRevocationStore revocationStore,
      AesGcmCipher aesGcmCipher,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        staffStore,
        eventStore,
        loginService,
        jwtService,
        revocationStore,
        aesGcmCipher,
        rateLimiter,
        clock,
        () -> MessageDigest.getInstance("SHA-256"));
  }

  AdminVerifyMfaService(
      AdminStaffStore staffStore,
      AdminAuthEventStore eventStore,
      AdminLoginService loginService,
      Rs256JwtService jwtService,
      TokenRevocationStore revocationStore,
      AesGcmCipher aesGcmCipher,
      RateLimiter rateLimiter,
      Clock clock,
      DigestFactory digestFactory) {
    this.staffStore = staffStore;
    this.eventStore = eventStore;
    this.loginService = loginService;
    this.jwtService = jwtService;
    this.revocationStore = revocationStore;
    this.aesGcmCipher = aesGcmCipher;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.digestFactory = digestFactory;
  }

  public AdminMfaVerifyResult verify(
      String challengeToken, String code, String clientIp, String userAgent) {
    if (challengeToken == null || challengeToken.isBlank() || code == null || code.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "mfa_challenge_token and code are required", 400);
    }

    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp;
    String ipKey = "admin:ip:verify-mfa:" + ip + ":count";
    if (!rateLimiter.tryAcquire(ipKey, MFA_RATE_LIMIT, MFA_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "IP MFA rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(ipKey, MFA_RATE_LIMIT, MFA_RATE_WINDOW_SECONDS));
    }

    JwtClaims claims = parseChallenge(challengeToken);
    Instant now = clock.instant();
    AdminStaffRecord admin =
        staffStore
            .findById(claims.subject())
            .orElseThrow(
                () -> new AppException("CHALLENGE_TOKEN_INVALID", "Challenge token invalid", 401));

    if (admin.lockedUntil() != null && now.isBefore(admin.lockedUntil())) {
      audit(admin.id(), "ACCOUNT_LOCKED", ip, userAgent, Map.of("email", admin.email()));
      throw new AppException(
          "ACCOUNT_LOCKED",
          "Account is locked due to too many failed attempts",
          403,
          null,
          Map.of("unlock_at", admin.lockedUntil().toString()));
    }

    boolean usedBackup = false;
    List<Map<String, Object>> backupCodes = new ArrayList<>(admin.backupCodes());
    String normalised = BackupCodes.normalise(code);

    if (BackupCodes.looksLikeBackupCode(normalised)) {
      String hash = sha256Hex(normalised);
      boolean matched = false;
      for (int i = 0; i < backupCodes.size(); i++) {
        Map<String, Object> row = backupCodes.get(i);
        if (hash.equals(String.valueOf(row.get("hash"))) && row.get("used_at") == null) {
          Map<String, Object> used = new HashMap<>(row);
          used.put("used_at", now.toString());
          backupCodes.set(i, used);
          matched = true;
          usedBackup = true;
          break;
        }
      }
      if (!matched) {
        failMfa(admin, now, ip, userAgent, "INVALID_BACKUP_CODE");
        throw new AppException(
            "INVALID_BACKUP_CODE", "Backup code does not match or already used", 400);
      }
    } else {
      if (admin.encryptedTotpSecret() == null || admin.encryptedTotpSecret().isBlank()) {
        failMfa(admin, now, ip, userAgent, "INVALID_MFA_CODE");
        throw new AppException("INVALID_MFA_CODE", "TOTP code is incorrect or expired", 400);
      }
      byte[] key = Base32.decode(aesGcmCipher.decrypt(admin.encryptedTotpSecret()));
      if (!Totp.verify(key, normalised, now)) {
        failMfa(admin, now, ip, userAgent, "INVALID_MFA_CODE");
        throw new AppException("INVALID_MFA_CODE", "TOTP code is incorrect or expired", 400);
      }
    }

    // Single-use challenge — first successful consumer wins (shared Redis store in deployed envs)
    if (!revocationStore.tryRevoke(claims.jti(), AdminLoginService.MFA_CHALLENGE_TTL_SECONDS)) {
      throw new AppException("CHALLENGE_TOKEN_INVALID", "Challenge token already used", 401);
    }
    AdminStaffRecord activated =
        AdminLoginService.copy(
            admin,
            true,
            admin.encryptedTotpSecret(),
            backupCodes,
            0,
            null,
            null,
            admin.lastLoginAt(),
            admin.lastActiveAt(),
            now);
    staffStore.save(activated);

    audit(
        admin.id(),
        "MFA_SUCCESS",
        ip,
        userAgent,
        Map.of("email", admin.email(), "used_backup_code", usedBackup));

    AdminLoginResult session = loginService.issueFullSession(activated, ip, userAgent, now);
    int remaining =
        (int) activated.backupCodes().stream().filter(r -> r.get("used_at") == null).count();
    return new AdminMfaVerifyResult(
        session.accessToken(),
        session.refreshToken(),
        session.accessTtlSeconds(),
        session.refreshTtlSeconds(),
        usedBackup,
        session.admin(),
        remaining);
  }

  private JwtClaims parseChallenge(String token) {
    try {
      JwtClaims claims = jwtService.parseAndValidate(token);
      if (claims.tokenScope() != TokenScope.MFA_CHALLENGE) {
        throw new AppException("CHALLENGE_TOKEN_INVALID", "Not a valid MFA challenge token", 401);
      }
      return claims;
    } catch (AppException ex) {
      throw ex;
    } catch (ExpiredJwtException ex) {
      throw new AppException("CHALLENGE_TOKEN_EXPIRED", "mfa_challenge_token has expired", 401);
    } catch (RuntimeException ex) {
      throw new AppException("CHALLENGE_TOKEN_INVALID", "Challenge token invalid", 401);
    }
  }

  private void failMfa(AdminStaffRecord admin, Instant now, String ip, String ua, String reason) {
    AdminStaffRecord updated = applyFailure(admin, now);
    staffStore.save(updated);
    audit(admin.id(), "MFA_FAILED", ip, ua, Map.of("email", admin.email(), "reason", reason));
    boolean newlyLocked =
        updated.lockedUntil() != null
            && !java.util.Objects.equals(updated.lockedUntil(), admin.lockedUntil());
    if (newlyLocked) {
      audit(admin.id(), "ACCOUNT_LOCKED", ip, ua, Map.of("email", admin.email()));
      throw new AppException(
          "ACCOUNT_LOCKED",
          "Account is locked due to too many failed attempts",
          403,
          null,
          Map.of("unlock_at", updated.lockedUntil().toString()));
    }
  }

  private AdminStaffRecord applyFailure(AdminStaffRecord admin, Instant now) {
    int attempts;
    if (admin.lastFailedAt() == null
        || admin
            .lastFailedAt()
            .isBefore(now.minus(AdminLoginService.FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES))) {
      attempts = 1;
    } else {
      attempts = admin.failedLoginAttempts() + 1;
    }
    Instant lockedUntil =
        attempts >= AdminLoginService.MAX_FAILED_ATTEMPTS
            ? now.plus(AdminLoginService.LOCKOUT_MINUTES, ChronoUnit.MINUTES)
            : admin.lockedUntil();
    return AdminLoginService.copy(
        admin,
        admin.mfaEnabled(),
        admin.encryptedTotpSecret(),
        admin.backupCodes(),
        attempts,
        lockedUntil,
        now,
        admin.lastLoginAt(),
        admin.lastActiveAt(),
        now);
  }

  private void audit(
      UUID adminId, String type, String ip, String ua, Map<String, Object> metadata) {
    eventStore.save(
        new AdminAuthEventRecord(Ids.newId(), adminId, type, ip, ua, metadata, clock.instant()));
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
