package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminAuthEventStore;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminLoginService {

  static final int LOGIN_RATE_LIMIT = 20;
  static final int LOGIN_RATE_WINDOW_SECONDS = 60;
  static final int MAX_FAILED_ATTEMPTS = 5;
  static final int FAILURE_WINDOW_MINUTES = 15;
  static final int LOCKOUT_MINUTES = 30;
  static final long ACCESS_TTL_SECONDS = 900L;
  static final long REFRESH_TTL_SECONDS = 28_800L;
  static final long MFA_CHALLENGE_TTL_SECONDS = 300L;

  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final AdminStaffStore staffStore;
  private final AdminAuthEventStore eventStore;
  private final AuthSessionStore sessionStore;
  private final PasswordEncoder staffPasswordEncoder;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final DigestFactory digestFactory;

  @Autowired
  public AdminLoginService(
      AdminStaffStore staffStore,
      AdminAuthEventStore eventStore,
      AuthSessionStore sessionStore,
      @Qualifier("staffPasswordEncoder") PasswordEncoder staffPasswordEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        staffStore,
        eventStore,
        sessionStore,
        staffPasswordEncoder,
        jwtService,
        rateLimiter,
        clock,
        new SecureRandom(),
        () -> MessageDigest.getInstance("SHA-256"));
  }

  AdminLoginService(
      AdminStaffStore staffStore,
      AdminAuthEventStore eventStore,
      AuthSessionStore sessionStore,
      PasswordEncoder staffPasswordEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock,
      SecureRandom secureRandom,
      DigestFactory digestFactory) {
    this.staffStore = staffStore;
    this.eventStore = eventStore;
    this.sessionStore = sessionStore;
    this.staffPasswordEncoder = staffPasswordEncoder;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.digestFactory = digestFactory;
  }

  public AdminLoginResult login(String email, String password, String clientIp, String userAgent) {
    String normalisedEmail = normaliseEmail(email);
    if (normalisedEmail == null || password == null || password.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "email and password are required", 400);
    }
    if (password.length() < 8 || !EMAIL.matcher(normalisedEmail).matches()) {
      throw new AppException("VALIDATION_ERROR", "Malformed email or password", 400);
    }

    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp;
    String ipKey = "admin:ip:login:" + ip + ":count";
    if (!rateLimiter.tryAcquire(ipKey, LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "IP login rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(ipKey, LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW_SECONDS));
    }

    Optional<AdminStaffRecord> found = staffStore.findByEmail(normalisedEmail);
    if (found.isEmpty()) {
      audit(
          null,
          "LOGIN_FAILED",
          ip,
          userAgent,
          Map.of("email", normalisedEmail, "reason", "ADMIN_NOT_FOUND"));
      throw new AppException("ADMIN_NOT_FOUND", "No admin account with that email", 404);
    }
    AdminStaffRecord admin = found.get();

    if ("SUSPENDED".equals(admin.status())) {
      audit(
          admin.id(),
          "LOGIN_FAILED",
          ip,
          userAgent,
          Map.of("email", normalisedEmail, "reason", "ACCOUNT_SUSPENDED"));
      throw new AppException("ACCOUNT_SUSPENDED", "Admin account has been suspended", 403);
    }

    Instant now = clock.instant();
    if (admin.lockedUntil() != null && now.isBefore(admin.lockedUntil())) {
      audit(admin.id(), "ACCOUNT_LOCKED", ip, userAgent, Map.of("email", normalisedEmail));
      throw locked(admin.lockedUntil());
    }

    if (!staffPasswordEncoder.matches(password, admin.passwordHash())) {
      AdminStaffRecord updated = applyFailure(admin, now);
      staffStore.save(updated);
      audit(
          admin.id(),
          "LOGIN_FAILED",
          ip,
          userAgent,
          Map.of("email", normalisedEmail, "reason", "INVALID_CREDENTIALS"));
      if (updated.lockedUntil() != null && !updated.lockedUntil().equals(admin.lockedUntil())) {
        audit(admin.id(), "ACCOUNT_LOCKED", ip, userAgent, Map.of("email", normalisedEmail));
        throw locked(updated.lockedUntil());
      }
      throw new AppException("INVALID_CREDENTIALS", "Password does not match", 401);
    }

    // Password OK — do not reset failure counters until full session (post-MFA if required).
    boolean isSuper = "admin_super".equals(admin.role());
    boolean hasSecret =
        admin.encryptedTotpSecret() != null && !admin.encryptedTotpSecret().isBlank();
    boolean mfaEnrolled = admin.mfaEnabled();

    if (isSuper && !hasSecret) {
      audit(
          admin.id(),
          "LOGIN_FAILED",
          ip,
          userAgent,
          Map.of("email", normalisedEmail, "reason", "MFA_ENROLLMENT_REQUIRED"));
      throw new AppException(
          "MFA_ENROLLMENT_REQUIRED", "admin_super must enroll MFA before signing in", 403);
    }

    boolean requireMfa = mfaEnrolled || hasSecret;
    if (requireMfa) {
      AuthRole role = AuthRole.fromValue(admin.role());
      String challenge =
          jwtService.issueAccessToken(
              new JwtClaims(
                  admin.id(), role, null, TokenScope.MFA_CHALLENGE, Ids.newId().toString()),
              MFA_CHALLENGE_TTL_SECONDS);
      return AdminLoginResult.challenge(challenge, MFA_CHALLENGE_TTL_SECONDS, admin.id());
    }

    return issueFullSession(admin, ip, userAgent, now);
  }

  AdminLoginResult issueFullSession(
      AdminStaffRecord admin, String ip, String userAgent, Instant now) {
    AuthRole role = AuthRole.fromValue(admin.role());
    String accessToken =
        jwtService.issueAccessToken(
            new JwtClaims(admin.id(), role, null, TokenScope.FULL, Ids.newId().toString()),
            ACCESS_TTL_SECONDS);
    String refreshToken = opaqueToken();
    Instant refreshExpires = now.plus(REFRESH_TTL_SECONDS, ChronoUnit.SECONDS);
    sessionStore.save(
        AuthSessionRecord.active(
            Ids.newId(),
            admin.id(),
            "admin_staff",
            sha256Hex(refreshToken),
            "full",
            null,
            ip,
            userAgent,
            now,
            now,
            refreshExpires,
            null));
    AdminStaffRecord loggedIn =
        copy(
            admin,
            admin.mfaEnabled(),
            admin.encryptedTotpSecret(),
            admin.backupCodes(),
            0,
            null,
            null,
            now,
            now,
            now);
    staffStore.save(loggedIn);
    audit(admin.id(), "LOGIN_SUCCESS", ip, userAgent, Map.of("email", admin.email()));
    return AdminLoginResult.tokens(
        accessToken, refreshToken, ACCESS_TTL_SECONDS, REFRESH_TTL_SECONDS, loggedIn);
  }

  private AdminStaffRecord applyFailure(AdminStaffRecord admin, Instant now) {
    int attempts;
    if (admin.lastFailedAt() == null
        || admin.lastFailedAt().isBefore(now.minus(FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES))) {
      attempts = 1;
    } else {
      attempts = admin.failedLoginAttempts() + 1;
    }
    Instant lockedUntil =
        attempts >= MAX_FAILED_ATTEMPTS
            ? now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES)
            : admin.lockedUntil();
    return copy(
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

  static AdminStaffRecord copy(
      AdminStaffRecord a,
      boolean mfaEnabled,
      String encryptedTotpSecret,
      java.util.List<Map<String, Object>> backupCodes,
      int failedLoginAttempts,
      Instant lockedUntil,
      Instant lastFailedAt,
      Instant lastLoginAt,
      Instant lastActiveAt,
      Instant updatedAt) {
    return new AdminStaffRecord(
        a.id(),
        a.name(),
        a.email(),
        a.passwordHash(),
        a.role(),
        a.status(),
        mfaEnabled,
        encryptedTotpSecret,
        backupCodes,
        failedLoginAttempts,
        lockedUntil,
        lastFailedAt,
        lastLoginAt,
        lastActiveAt,
        a.invitedBy(),
        a.createdAt(),
        updatedAt);
  }

  private void audit(
      UUID adminId, String type, String ip, String ua, Map<String, Object> metadata) {
    eventStore.save(
        new AdminAuthEventRecord(Ids.newId(), adminId, type, ip, ua, metadata, clock.instant()));
  }

  private static AppException locked(Instant unlockAt) {
    return new AppException(
        "ACCOUNT_LOCKED",
        "Account is locked due to too many failed attempts",
        403,
        null,
        Map.of("unlock_at", unlockAt.toString()));
  }

  static String normaliseEmail(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String opaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
