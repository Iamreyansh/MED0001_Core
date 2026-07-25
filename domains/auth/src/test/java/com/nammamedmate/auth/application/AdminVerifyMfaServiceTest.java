package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminAuthEventStore;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.domain.BackupCodes;
import com.nammamedmate.auth.domain.Base32;
import com.nammamedmate.auth.domain.Totp;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenRevocationStore;
import com.nammamedmate.security.TokenScope;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminVerifyMfaServiceTest {

  private static final Instant NOW = Instant.now();
  private static final byte[] AES_KEY = new byte[32];
  private static final byte[] TOTP_SECRET_BYTES = new byte[20];

  private final MutableClock clock = new MutableClock(NOW);
  private FakeStaffStore staffStore;
  private FakeEventStore eventStore;
  private FakeSessionStore sessionStore;
  private InMemoryRateLimiter rateLimiter;
  private InMemoryTokenRevocationStore revocationStore;
  private Rs256JwtService jwt;
  private KeyPair keyPair;
  private AesGcmCipher cipher;
  private AdminLoginService loginService;
  private AdminVerifyMfaService service;

  private final UUID adminId = Ids.newId();
  private String encryptedSecret;
  private String plaintextSecret;
  private List<Map<String, Object>> storedBackupCodes;
  private String backupCodePlain;

  @BeforeEach
  void setUp() throws Exception {
    staffStore = new FakeStaffStore();
    eventStore = new FakeEventStore();
    sessionStore = new FakeSessionStore();
    rateLimiter = new InMemoryRateLimiter(clock);
    revocationStore = new InMemoryTokenRevocationStore(clock);

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keyPair = gen.generateKeyPair();
    jwt =
        new Rs256JwtService(keyPair.getPrivate(), keyPair.getPublic(), revocationStore, clock, 900);

    SecureRandom fixedIv = new SecureRandom(new byte[] {4, 2, 4, 2, 4, 2, 4, 2});
    cipher = new AesGcmCipher(AES_KEY, fixedIv);
    plaintextSecret = Base32.encode(TOTP_SECRET_BYTES);
    encryptedSecret = cipher.encrypt(plaintextSecret);

    backupCodePlain = "ABCD-1234";

    loginService =
        new AdminLoginService(
            staffStore,
            eventStore,
            sessionStore,
            new BCryptPasswordEncoder(12),
            jwt,
            rateLimiter,
            clock);

    service =
        new AdminVerifyMfaService(
            staffStore, eventStore, loginService, jwt, revocationStore, cipher, rateLimiter, clock);

    storedBackupCodes =
        BackupCodes.toStoredRows(
            List.of(backupCodePlain, "WXYZ-9876"), value -> service.sha256Hex(value));

    staffStore.byId.put(
        adminId,
        admin(
            adminId,
            "super@test.in",
            "admin_super",
            true,
            encryptedSecret,
            storedBackupCodes,
            0,
            null,
            null));
  }

  @Test
  void autowiredConstructorCoversDefaults() {
    AdminVerifyMfaService autowired =
        new AdminVerifyMfaService(
            staffStore, eventStore, loginService, jwt, revocationStore, cipher, rateLimiter, clock);
    String challenge = issueChallenge("jti-default");
    String code = Totp.generate(TOTP_SECRET_BYTES, NOW);
    AdminMfaVerifyResult result = autowired.verify(challenge, code, "1.1.1.1", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void totpSuccessIssuesTokensAndRevokesChallenge() {
    String jti = "jti-totp-ok";
    String challenge = issueChallenge(jti);
    String code = Totp.generate(TOTP_SECRET_BYTES, NOW);

    AdminMfaVerifyResult result = service.verify(challenge, code, "1.1.1.1", "ua");

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.accessTtlSeconds()).isEqualTo(900L);
    assertThat(result.refreshTtlSeconds()).isEqualTo(28_800L);
    assertThat(result.usedBackupCode()).isFalse();
    assertThat(result.admin().mfaEnabled()).isTrue();
    assertThat(result.backupCodesRemaining()).isEqualTo(2);
    assertThat(revocationStore.isRevoked(jti)).isTrue();
    assertThat(eventStore.records).anyMatch(e -> "MFA_SUCCESS".equals(e.eventType()));
    assertThat(sessionStore.saved).hasSize(1);
  }

  @Test
  void concurrentChallengeConsumeRejectsSecondWinner() {
    TokenRevocationStore loseRace =
        new TokenRevocationStore() {
          @Override
          public boolean isRevoked(String jti) {
            return false;
          }

          @Override
          public void revoke(String jti, long ttlSeconds) {}

          @Override
          public boolean tryRevoke(String jti, long ttlSeconds) {
            return false;
          }
        };
    AdminVerifyMfaService racing =
        new AdminVerifyMfaService(
            staffStore, eventStore, loginService, jwt, loseRace, cipher, rateLimiter, clock);
    String challenge = issueChallenge("jti-race");
    String code = Totp.generate(TOTP_SECRET_BYTES, NOW);

    assertThatThrownBy(() -> racing.verify(challenge, code, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void backupCodeSuccessMarksUsedAndDecrementsRemaining() {
    String challenge = issueChallenge("jti-backup");
    AdminMfaVerifyResult result = service.verify(challenge, backupCodePlain, "1.1.1.1", "ua");

    assertThat(result.usedBackupCode()).isTrue();
    assertThat(result.backupCodesRemaining()).isEqualTo(1);
    AdminStaffRecord saved = staffStore.byId.get(adminId);
    assertThat(saved.backupCodes().get(0).get("used_at")).isNotNull();
  }

  @Test
  void invalidMfaCode() {
    String challenge = issueChallenge("jti-bad-totp");
    assertThatThrownBy(() -> service.verify(challenge, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
    assertThat(eventStore.records).anyMatch(e -> "MFA_FAILED".equals(e.eventType()));
  }

  @Test
  void invalidBackupCode() {
    String challenge = issueChallenge("jti-bad-backup");
    assertThatThrownBy(() -> service.verify(challenge, "ZZZZ-9999", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_BACKUP_CODE");
  }

  @Test
  void backupCodeAlreadyUsed() {
    String challenge = issueChallenge("jti-used-backup");
    service.verify(challenge, backupCodePlain, "1.1.1.1", "ua");

    String challenge2 = issueChallenge("jti-used-backup-2");
    assertThatThrownBy(() -> service.verify(challenge2, backupCodePlain, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_BACKUP_CODE");
  }

  @Test
  void challengeTokenExpired() {
    MutableClock pastClock = new MutableClock(Instant.now().minusSeconds(10));
    Rs256JwtService pastJwt =
        new Rs256JwtService(
            keyPair.getPrivate(), keyPair.getPublic(), revocationStore, pastClock, 900);
    String challenge =
        pastJwt.issueAccessToken(
            new JwtClaims(
                adminId, AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti-expired"),
            1L);
    assertThatThrownBy(() -> service.verify(challenge, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_EXPIRED");
  }

  @Test
  void challengeTokenInvalidWrongScope() {
    String fullToken =
        jwt.issueAccessToken(
            new JwtClaims(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "jti-full"), 300L);
    assertThatThrownBy(() -> service.verify(fullToken, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void challengeTokenInvalidTampered() {
    assertThatThrownBy(() -> service.verify("not-a-jwt", "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void challengeTokenInvalidWhenRevoked() {
    String jti = "jti-revoked";
    String challenge = issueChallenge(jti);
    revocationStore.revoke(jti, 300);
    assertThatThrownBy(() -> service.verify(challenge, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void accountLockedOnMfaFailures() {
    for (int i = 0; i < 4; i++) {
      final String attemptChallenge = issueChallenge("jti-lock-" + i);
      assertThatThrownBy(() -> service.verify(attemptChallenge, "000000", "1.1.1.1", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_MFA_CODE");
    }
    AppException ex =
        (AppException)
            catchThrowable(
                () -> service.verify(issueChallenge("jti-lock-final"), "000000", "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
  }

  @Test
  void alreadyLockedBeforeMfaVerify() {
    Instant lockedUntil = NOW.plusSeconds(1800);
    staffStore.byId.put(
        adminId,
        admin(
            adminId,
            "super@test.in",
            "admin_super",
            true,
            encryptedSecret,
            storedBackupCodes,
            5,
            lockedUntil,
            NOW.minusSeconds(10)));

    String challenge = issueChallenge("jti-prelocked");
    assertThatThrownBy(() -> service.verify(challenge, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  void mfaFailureLocksOnFifthAttempt() {
    for (int i = 0; i < 4; i++) {
      final String attempt = issueChallenge("jti-mfa-lock-" + i);
      assertThatThrownBy(() -> service.verify(attempt, "000000", "3.3.3.3", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_MFA_CODE");
    }
    assertThatThrownBy(
            () -> service.verify(issueChallenge("jti-mfa-lock-final"), "000000", "3.3.3.3", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_LOCKED");
    assertThat(
            eventStore.records.stream().filter(e -> "ACCOUNT_LOCKED".equals(e.eventType())).count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void verifyProceedsWhenLockExpired() {
    staffStore.byId.put(
        adminId,
        admin(
            adminId,
            "super@test.in",
            "admin_super",
            true,
            encryptedSecret,
            storedBackupCodes,
            0,
            NOW.minusSeconds(60),
            null));

    assertThatThrownBy(
            () -> service.verify(issueChallenge("jti-expired-lock"), "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
  }

  @Test
  void rejectsTotpWhenSecretBlank() {
    staffStore.byId.put(
        adminId,
        admin(adminId, "super@test.in", "admin_super", false, "   ", List.of(), 0, null, null));

    assertThatThrownBy(
            () -> service.verify(issueChallenge("jti-blank-secret"), "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
  }

  @Test
  void challengeAdminNotFound() {
    UUID missing = Ids.newId();
    String challenge =
        jwt.issueAccessToken(
            new JwtClaims(
                missing, AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti-miss"),
            300L);
    assertThatThrownBy(() -> service.verify(challenge, "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void blankClientIpOnVerify() {
    String challenge = issueChallenge("jti-ip");
    String code = Totp.generate(TOTP_SECRET_BYTES, NOW);
    AdminMfaVerifyResult result = service.verify(challenge, code, "   ", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void validationRejectsBlankCodeOnly() {
    assertThatThrownBy(() -> service.verify(issueChallenge("jti-v"), "   ", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify("   ", "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void nullClientIpOnVerify() {
    String challenge = issueChallenge("jti-null-ip");
    String code = Totp.generate(TOTP_SECRET_BYTES, NOW);
    AdminMfaVerifyResult result = service.verify(challenge, code, null, "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void mfaFailureWindowResetsAfterFifteenMinutes() {
    String challenge = issueChallenge("jti-window-1");
    assertThatThrownBy(() -> service.verify(challenge, "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
    assertThat(staffStore.byId.get(adminId).failedLoginAttempts()).isEqualTo(1);

    clock.advanceSeconds(16 * 60);
    assertThatThrownBy(
            () -> service.verify(issueChallenge("jti-window-2"), "000000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
    assertThat(staffStore.byId.get(adminId).failedLoginAttempts()).isEqualTo(1);
  }

  @Test
  void validationErrors() {
    assertThatThrownBy(() -> service.verify(null, "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify("token", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify("  ", "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void ipRateLimitEnforced() {
    String challenge = issueChallenge("jti-rate");
    for (int i = 0; i < 10; i++) {
      try {
        service.verify(challenge, "000000", "9.9.9.9", "ua");
      } catch (Exception ignored) {
      }
      challenge = issueChallenge("jti-rate-" + i);
    }
    assertThatThrownBy(
            () -> service.verify(issueChallenge("jti-rate-final"), "000000", "9.9.9.9", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void missingTotpSecretRejectsTotpCode() {
    staffStore.byId.put(
        adminId,
        admin(adminId, "super@test.in", "admin_super", false, null, List.of(), 0, null, null));
    String challenge = issueChallenge("jti-no-secret");
    assertThatThrownBy(() -> service.verify(challenge, "123456", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MFA_CODE");
  }

  @Test
  void sha256HexThrowsOnDigestError() {
    AdminVerifyMfaService badDigest =
        new AdminVerifyMfaService(
            staffStore,
            eventStore,
            loginService,
            jwt,
            revocationStore,
            cipher,
            rateLimiter,
            clock,
            () -> {
              throw new java.security.NoSuchAlgorithmException("test");
            });
    assertThatThrownBy(() -> badDigest.sha256Hex("value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 not available");
  }

  private String issueChallenge(String jti) {
    return jwt.issueAccessToken(
        new JwtClaims(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, jti), 300L);
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private static AdminStaffRecord admin(
      UUID id,
      String email,
      String role,
      boolean mfaEnabled,
      String secret,
      List<Map<String, Object>> backupCodes,
      int failed,
      Instant lockedUntil,
      Instant lastFailed) {
    return new AdminStaffRecord(
        id,
        "Admin",
        email,
        "hash",
        role,
        "ACTIVE",
        mfaEnabled,
        secret,
        backupCodes,
        failed,
        lockedUntil,
        lastFailed,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static final class FakeStaffStore implements AdminStaffStore {
    final Map<UUID, AdminStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<AdminStaffRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public Optional<AdminStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public AdminStaffRecord save(AdminStaffRecord staff) {
      byId.put(staff.id(), staff);
      return staff;
    }
  }

  private static final class FakeEventStore implements AdminAuthEventStore {
    final List<AdminAuthEventRecord> records = new ArrayList<>();

    @Override
    public void save(AdminAuthEventRecord event) {
      records.add(event);
    }
  }

  private static final class FakeSessionStore
      implements com.nammamedmate.auth.application.port.out.AuthSessionStore {
    final List<AuthSessionRecord> saved = new ArrayList<>();

    @Override
    public AuthSessionRecord save(AuthSessionRecord session) {
      saved.add(session);
      return session;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
