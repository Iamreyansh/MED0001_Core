package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminAuthEventStore;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.Rs256JwtService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminLoginServiceTest {

  private static final String PASSWORD = "Passw0rd!";
  private static final Instant NOW = Instant.now();

  private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final MutableClock clock = new MutableClock(NOW);
  private FakeStaffStore staffStore;
  private FakeEventStore eventStore;
  private FakeSessionStore sessionStore;
  private InMemoryRateLimiter rateLimiter;
  private Rs256JwtService jwt;
  private AdminLoginService service;

  private final UUID opsId = Ids.newId();
  private final UUID superId = Ids.newId();
  private final UUID opsSecretId = Ids.newId();
  private final UUID superNoMfaId = Ids.newId();
  private String passHash;

  @BeforeEach
  void setUp() throws Exception {
    staffStore = new FakeStaffStore();
    eventStore = new FakeEventStore();
    sessionStore = new FakeSessionStore();
    rateLimiter = new InMemoryRateLimiter(clock);

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    jwt =
        new Rs256JwtService(
            pair.getPrivate(),
            pair.getPublic(),
            new InMemoryTokenRevocationStore(clock),
            clock,
            900);

    service =
        new AdminLoginService(
            staffStore, eventStore, sessionStore, encoder, jwt, rateLimiter, clock);

    passHash = encoder.encode(PASSWORD);

    staffStore.byEmail.put(
        "ops@test.in",
        admin(opsId, "ops@test.in", "admin_operations", false, null, "ACTIVE", 0, null, null));
    staffStore.byEmail.put(
        "super@test.in",
        admin(
            superId,
            "super@test.in",
            "admin_super",
            true,
            "encrypted-secret",
            "ACTIVE",
            0,
            null,
            null));
    staffStore.byEmail.put(
        "ops-secret@test.in",
        admin(
            opsSecretId,
            "ops-secret@test.in",
            "admin_operations",
            false,
            "pending-secret",
            "ACTIVE",
            0,
            null,
            null));
    staffStore.byEmail.put(
        "super-nomfa@test.in",
        admin(
            superNoMfaId,
            "super-nomfa@test.in",
            "admin_super",
            false,
            null,
            "ACTIVE",
            0,
            null,
            null));
    staffStore.byEmail.put(
        "ops-mfa@test.in",
        admin(
            Ids.newId(),
            "ops-mfa@test.in",
            "admin_operations",
            true,
            null,
            "ACTIVE",
            0,
            null,
            null));
  }

  @Test
  void autowiredConstructorCoversDefaults() {
    AdminLoginService autowired =
        new AdminLoginService(
            staffStore, eventStore, sessionStore, encoder, jwt, rateLimiter, clock);
    AdminLoginResult result = autowired.login("ops@test.in", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void loginRejectsNullPasswordHashForInvited() {
    UUID invitedId = Ids.newId();
    AdminStaffRecord invited =
        new AdminStaffRecord(
            invitedId,
            "Invited",
            "invited@test.in",
            null,
            "admin_support",
            "INVITED",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byEmail.put("invited@test.in", invited);
    assertThatThrownBy(() -> service.login("invited@test.in", PASSWORD, "127.0.0.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void loginRejectsBlankPasswordHash() {
    UUID invitedId = Ids.newId();
    staffStore.byEmail.put(
        "blankhash@test.in",
        new AdminStaffRecord(
            invitedId,
            "Invited",
            "blankhash@test.in",
            "   ",
            "admin_support",
            "INVITED",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThatThrownBy(() -> service.login("blankhash@test.in", PASSWORD, "127.0.0.1", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void loginHappyPathNoMfa() {
    AdminLoginResult result = service.login("ops@test.in", PASSWORD, "1.1.1.1", "ua");

    assertThat(result.mfaRequired()).isFalse();
    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.accessTtlSeconds()).isEqualTo(900L);
    assertThat(result.refreshTtlSeconds()).isEqualTo(28_800L);
    assertThat(sessionStore.saved).hasSize(1);
    assertThat(eventStore.records).anyMatch(e -> "LOGIN_SUCCESS".equals(e.eventType()));
    assertThat(staffStore.byEmail.get("ops@test.in").failedLoginAttempts()).isZero();
  }

  @Test
  void superWithSecretRequiresMfaChallenge() {
    AdminLoginResult result = service.login("super@test.in", PASSWORD, "1.1.1.1", "ua");

    assertThat(result.mfaRequired()).isTrue();
    assertThat(result.mfaChallengeToken()).isNotBlank();
    assertThat(result.accessToken()).isNull();
    assertThat(result.mfaChallengeExpiresIn()).isEqualTo(300L);
    assertThat(sessionStore.saved).isEmpty();
  }

  @Test
  void nonSuperWithMfaEnabledRequiresChallenge() {
    AdminLoginResult result = service.login("ops-mfa@test.in", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.mfaRequired()).isTrue();
    assertThat(result.mfaChallengeToken()).isNotBlank();
  }

  @Test
  void nonSuperWithUnenrolledSecretRequiresChallenge() {
    AdminLoginResult result = service.login("ops-secret@test.in", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.mfaRequired()).isTrue();
    assertThat(result.mfaChallengeToken()).isNotBlank();
  }

  @Test
  void superWithoutSecretRequiresEnrollment() {
    assertThatThrownBy(() -> service.login("super-nomfa@test.in", PASSWORD, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MFA_ENROLLMENT_REQUIRED");
    assertThat(eventStore.records)
        .anyMatch(
            e ->
                "LOGIN_FAILED".equals(e.eventType())
                    && e.metadata().get("reason").equals("MFA_ENROLLMENT_REQUIRED"));
  }

  @Test
  void adminNotFound() {
    assertThatThrownBy(() -> service.login("nobody@test.in", PASSWORD, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADMIN_NOT_FOUND");
    assertThat(eventStore.records).anyMatch(e -> "LOGIN_FAILED".equals(e.eventType()));
  }

  @Test
  void invalidCredentials() {
    assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "9.9.9.9", "ua-fail"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(staffStore.byEmail.get("ops@test.in").failedLoginAttempts()).isEqualTo(1);
    assertThat(eventStore.records)
        .anyMatch(
            e ->
                "LOGIN_FAILED".equals(e.eventType())
                    && "INVALID_CREDENTIALS".equals(e.metadata().get("reason"))
                    && "9.9.9.9".equals(e.ipAddress()));
  }

  @Test
  void accountSuspended() {
    staffStore.byEmail.put(
        "ops@test.in",
        admin(opsId, "ops@test.in", "admin_operations", false, null, "SUSPENDED", 0, null, null));

    assertThatThrownBy(() -> service.login("ops@test.in", PASSWORD, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_SUSPENDED");
  }

  @Test
  void alreadyLockedAccount() {
    Instant lockedUntil = NOW.plusSeconds(1800);
    staffStore.byEmail.put(
        "ops@test.in",
        admin(
            opsId,
            "ops@test.in",
            "admin_operations",
            false,
            null,
            "ACTIVE",
            5,
            lockedUntil,
            NOW.minusSeconds(10)));

    AppException ex =
        (AppException)
            catchThrowable(() -> service.login("ops@test.in", PASSWORD, "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
  }

  @Test
  void fiveConsecutiveFailuresLocksAccount() {
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "1.1.1.1", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_CREDENTIALS");
    }
    AppException ex =
        (AppException)
            catchThrowable(() -> service.login("ops@test.in", "WrongPass1!", "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
    assertThat(eventStore.records).anyMatch(e -> "ACCOUNT_LOCKED".equals(e.eventType()));
  }

  @Test
  void passwordSuccessBeforeMfaDoesNotResetFailureCounter() {
    UUID mfaOpsId = staffStore.byEmail.get("ops-mfa@test.in").id();
    staffStore.byEmail.put(
        "ops-mfa@test.in",
        admin(
            mfaOpsId,
            "ops-mfa@test.in",
            "admin_operations",
            true,
            "enc-secret",
            "ACTIVE",
            3,
            null,
            NOW.minusSeconds(30)));

    AdminLoginResult result = service.login("ops-mfa@test.in", PASSWORD, "1.1.1.1", "ua");

    assertThat(result.mfaRequired()).isTrue();
    assertThat(staffStore.byEmail.get("ops-mfa@test.in").failedLoginAttempts()).isEqualTo(3);
  }

  @Test
  void failureWindowResetsAfterFifteenMinutes() {
    assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(staffStore.byEmail.get("ops@test.in").failedLoginAttempts()).isEqualTo(1);

    clock.advance(NOW.plusSeconds(16 * 60));

    assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(staffStore.byEmail.get("ops@test.in").failedLoginAttempts()).isEqualTo(1);
  }

  @Test
  void validationErrors() {
    assertThatThrownBy(() -> service.login(null, PASSWORD, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("ops@test.in", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("   ", PASSWORD, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("ops@test.in", "   ", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("ops@test.in", "short", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("not-an-email", PASSWORD, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void ipRateLimitEnforced() {
    for (int i = 0; i < 20; i++) {
      try {
        service.login("ops@test.in", "WrongPass1!", "9.9.9.9", "ua");
      } catch (Exception ignored) {
      }
    }
    assertThatThrownBy(() -> service.login("ops@test.in", PASSWORD, "9.9.9.9", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void wrongPasswordWithExpiredLockIncrementsAttemptsWithoutLocking() {
    staffStore.byEmail.put(
        "ops@test.in",
        admin(
            opsId,
            "ops@test.in",
            "admin_operations",
            false,
            null,
            "ACTIVE",
            2,
            NOW.minusSeconds(120),
            NOW.minusSeconds(30)));

    assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "4.4.4.4", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(staffStore.byEmail.get("ops@test.in").failedLoginAttempts()).isEqualTo(3);
  }

  @Test
  void requireMfaWhenOnlyFinanceRoleEnrolled() {
    staffStore.byEmail.put(
        "finance@test.in",
        admin(
            Ids.newId(), "finance@test.in", "admin_finance", true, null, "ACTIVE", 0, null, null));

    AdminLoginResult result = service.login("finance@test.in", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.mfaRequired()).isTrue();
  }

  @Test
  void fifthFailureAuditsAccountLocked() {
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "2.2.2.2", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_CREDENTIALS");
    }
    assertThatThrownBy(() -> service.login("ops@test.in", "WrongPass1!", "2.2.2.2", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_LOCKED");
    assertThat(
            eventStore.records.stream().filter(e -> "ACCOUNT_LOCKED".equals(e.eventType())).count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void expiredLockAllowsLogin() {
    staffStore.byEmail.put(
        "ops@test.in",
        admin(
            opsId,
            "ops@test.in",
            "admin_operations",
            false,
            null,
            "ACTIVE",
            2,
            NOW.minusSeconds(60),
            NOW.minusSeconds(120)));

    AdminLoginResult result = service.login("ops@test.in", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void blankClientIpDefaultsToZeroAddress() {
    AdminLoginResult result = service.login("ops@test.in", PASSWORD, "   ", "ua");
    assertThat(result.accessToken()).isNotBlank();
    assertThat(sessionStore.saved.get(0).ipAddress()).isEqualTo("0.0.0.0");
  }

  @Test
  void superWithBlankSecretRequiresEnrollment() {
    staffStore.byEmail.put(
        "super-blank@test.in",
        admin(
            Ids.newId(),
            "super-blank@test.in",
            "admin_super",
            false,
            "   ",
            "ACTIVE",
            0,
            null,
            null));

    assertThatThrownBy(() -> service.login("super-blank@test.in", PASSWORD, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MFA_ENROLLMENT_REQUIRED");
  }

  @Test
  void wrongPasswordWhenAlreadyLockedInSameWindowThrowsLocked() {
    Instant lockedUntil = NOW.plusSeconds(1800);
    staffStore.byEmail.put(
        "ops@test.in",
        admin(
            opsId,
            "ops@test.in",
            "admin_operations",
            false,
            null,
            "ACTIVE",
            5,
            lockedUntil,
            NOW.minusSeconds(60)));

    // expired lock window on lockedUntil but still within failure window
    clock.advance(NOW.plusSeconds(1));
    staffStore.byEmail.put(
        "ops@test.in",
        admin(
            opsId,
            "ops@test.in",
            "admin_operations",
            false,
            null,
            "ACTIVE",
            4,
            null,
            NOW.minusSeconds(30)));

    AppException ex =
        (AppException)
            catchThrowable(() -> service.login("ops@test.in", "WrongPass1!", "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  void nullClientIpDefaultsToZeroAddress() {
    AdminLoginResult result = service.login("ops@test.in", PASSWORD, null, "ua");
    assertThat(result.accessToken()).isNotBlank();
    assertThat(sessionStore.saved.get(0).ipAddress()).isEqualTo("0.0.0.0");
  }

  @Test
  void normaliseEmailTrimsAndLowercases() {
    AdminLoginResult result = service.login("  OPS@test.in  ", PASSWORD, "1.1.1.1", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void sha256HexThrowsOnDigestError() {
    AdminLoginService badDigest =
        new AdminLoginService(
            staffStore,
            eventStore,
            sessionStore,
            encoder,
            jwt,
            rateLimiter,
            clock,
            new java.security.SecureRandom(),
            () -> {
              throw new java.security.NoSuchAlgorithmException("test");
            });
    assertThatThrownBy(() -> badDigest.sha256Hex("value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 not available");
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private AdminStaffRecord admin(
      UUID id,
      String email,
      String role,
      boolean mfaEnabled,
      String secret,
      String status,
      int failed,
      Instant lockedUntil,
      Instant lastFailed) {
    return new AdminStaffRecord(
        id,
        "Admin",
        email,
        passHash,
        role,
        status,
        mfaEnabled,
        secret,
        List.of(),
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
    final Map<String, AdminStaffRecord> byEmail = new HashMap<>();
    final Map<UUID, AdminStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<AdminStaffRecord> findByEmail(String email) {
      return Optional.ofNullable(byEmail.get(email));
    }

    @Override
    public Optional<AdminStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public AdminStaffRecord save(AdminStaffRecord staff) {
      byEmail.put(staff.email(), staff);
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

  private static final class FakeSessionStore implements AuthSessionStore {
    final List<AuthSessionRecord> saved = new ArrayList<>();

    @Override
    public AuthSessionRecord save(AuthSessionRecord session) {
      saved.add(session);
      return session;
    }

    @Override
    public Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash) {
      return Optional.empty();
    }

    @Override
    public Optional<AuthSessionRecord> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public int markRotatedIfActive(UUID id, Instant rotatedAt) {
      return 0;
    }

    @Override
    public int revokeIfActive(UUID id, Instant revokedAt) {
      return 0;
    }

    @Override
    public int revokeAllForUser(UUID userId, Instant revokedAt) {
      return 0;
    }

    @Override
    public List<AuthSessionRecord> listActiveByUserId(
        UUID userId, Instant now, int page, int limit) {
      return List.of();
    }

    @Override
    public long countActiveByUserId(UUID userId, Instant now) {
      return 0;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Instant instant) {
      this.instant = instant;
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
