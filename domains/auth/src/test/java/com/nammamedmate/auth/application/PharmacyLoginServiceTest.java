package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.LoginAuditRecord;
import com.nammamedmate.auth.application.port.out.LoginAuditStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
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

class PharmacyLoginServiceTest {

  private static final String PASSWORD = "Passw0rd!";
  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final MutableClock clock = new MutableClock(NOW);
  private FakeStaffStore staffStore;
  private FakeAssignmentStore assignmentStore;
  private FakePharmacyStore pharmacyStore;
  private FakeSessionStore sessionStore;
  private FakeAuditStore auditStore;
  private InMemoryRateLimiter rateLimiter;
  private Rs256JwtService jwt;
  private PharmacyLoginService service;

  private final UUID pharmacyId = Ids.newId();
  private final UUID staffId = Ids.newId();
  private final UUID roleId = Ids.newId();
  private PharmacyStaffRecord baseStaff;
  private PharmacyAssignmentRecord baseAssignment;

  @BeforeEach
  void setUp() throws Exception {
    staffStore = new FakeStaffStore();
    assignmentStore = new FakeAssignmentStore();
    pharmacyStore = new FakePharmacyStore();
    sessionStore = new FakeSessionStore();
    auditStore = new FakeAuditStore();
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

    // Use @Autowired constructor to cover it (and the default DigestFactory lambda)
    service =
        new PharmacyLoginService(
            staffStore,
            assignmentStore,
            pharmacyStore,
            sessionStore,
            auditStore,
            encoder,
            jwt,
            rateLimiter,
            clock);

    baseStaff = staff("priya@test.in", null, encoder.encode(PASSWORD), 0, null, null);
    staffStore.byEmail.put("priya@test.in", baseStaff);

    baseAssignment = assignment(staffId, pharmacyId, "owner", "Sri Rama Medicals");
    assignmentStore.byStaffId.put(staffId, List.of(baseAssignment));
    assignmentStore.byStaffAndPharmacy.put(staffId + ":" + pharmacyId, baseAssignment);

    pharmacyStore.byId.put(
        pharmacyId,
        new PharmacyRecord(pharmacyId, "Sri Rama Medicals", null, "Bengaluru", "GROWTH"));
  }

  @Test
  void loginHappyPath() {
    PharmacyLoginResult result = service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua");

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.activePharmacy().name()).isEqualTo("Sri Rama Medicals");
    assertThat(result.roleInActivePharmacy()).isEqualTo("owner");
    assertThat(result.staff().failedLoginAttempts()).isZero();
    assertThat(sessionStore.saved).hasSize(1);
    assertThat(auditStore.records).hasSize(1);
    assertThat(auditStore.records.get(0).success()).isTrue();
  }

  @Test
  void loginWithPhoneIdentifier() {
    PharmacyStaffRecord phoneStaff =
        staff(null, "+919876543210", encoder.encode(PASSWORD), 0, null, null);
    staffStore.byPhone.put("+919876543210", phoneStaff);
    assignmentStore.byStaffId.put(
        phoneStaff.id(),
        List.of(assignment(phoneStaff.id(), pharmacyId, "pharmacist", "Sri Rama Medicals")));
    assignmentStore.byStaffAndPharmacy.put(
        phoneStaff.id() + ":" + pharmacyId,
        assignment(phoneStaff.id(), pharmacyId, "pharmacist", "Sri Rama Medicals"));

    PharmacyLoginResult result = service.login("+91 9876543210", PASSWORD, null, "1.1.1.1", "ua");
    assertThat(result.accessToken()).isNotBlank();
  }

  @Test
  void invalidPasswordReturnsUnauthorized() {
    assertThatThrownBy(
            () -> service.login("priya@test.in", "WrongPass1!", null, "9.9.9.9", "ua-fail"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(auditStore.records).isNotEmpty();
    assertThat(auditStore.records.get(0).success()).isFalse();
    assertThat(auditStore.records.get(0).failureReason()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(auditStore.records.get(0).ipAddress()).isEqualTo("9.9.9.9");
    assertThat(auditStore.records.get(0).userAgent()).isEqualTo("ua-fail");
  }

  @Test
  void staffNotFoundReturns404() {
    assertThatThrownBy(() -> service.login("nobody@x.com", PASSWORD, null, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void suspendedAccountReturns403() {
    PharmacyStaffRecord suspended =
        new PharmacyStaffRecord(
            staffId,
            "P",
            "priya@test.in",
            null,
            encoder.encode(PASSWORD),
            null,
            "SUSPENDED",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byEmail.put("priya@test.in", suspended);

    assertThatThrownBy(() -> service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_SUSPENDED");
  }

  @Test
  void lockedAccountReturns403WithUnlockAt() {
    Instant lockedUntil = NOW.plusSeconds(1800);
    PharmacyStaffRecord locked =
        new PharmacyStaffRecord(
            staffId,
            "P",
            "priya@test.in",
            null,
            encoder.encode(PASSWORD),
            null,
            "ACTIVE",
            5,
            lockedUntil,
            NOW.minusSeconds(10),
            null,
            null,
            NOW,
            NOW);
    staffStore.byEmail.put("priya@test.in", locked);

    AppException ex =
        (AppException)
            catchThrowable(() -> service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
  }

  @Test
  void expiredLockAllowsLoginAndWrongPasswordDoesNotReThrowLocked() {
    PharmacyStaffRecord expiredLock =
        new PharmacyStaffRecord(
            staffId,
            "P",
            "priya@test.in",
            null,
            encoder.encode(PASSWORD),
            null,
            "ACTIVE",
            2,
            NOW.minusSeconds(60),
            NOW.minusSeconds(120),
            null,
            null,
            NOW,
            NOW);
    staffStore.byEmail.put("priya@test.in", expiredLock);

    PharmacyLoginResult ok = service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua");
    assertThat(ok.accessToken()).isNotBlank();

    staffStore.byEmail.put("priya@test.in", expiredLock);
    assertThatThrownBy(() -> service.login("priya@test.in", "wrong", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void fiveConsecutiveFailuresLocksAccount() {
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login("priya@test.in", "wrong", null, "1.1.1.1", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_CREDENTIALS");
    }
    // 5th failure should lock
    AppException ex =
        (AppException)
            catchThrowable(() -> service.login("priya@test.in", "wrong", null, "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
  }

  @Test
  void failureWindowResetAfterTenMinutes() {
    assertThatThrownBy(() -> service.login("priya@test.in", "wrong", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(staffStore.byEmail.get("priya@test.in").failedLoginAttempts()).isEqualTo(1);

    // advance clock past the 10-minute window
    clock.advance(Instant.parse("2026-07-25T08:11:00Z"));

    assertThatThrownBy(() -> service.login("priya@test.in", "wrong", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CREDENTIALS");
    // counter reset to 1 (new window)
    assertThat(staffStore.byEmail.get("priya@test.in").failedLoginAttempts()).isEqualTo(1);
  }

  @Test
  void validationErrorOnMissingIdentifier() {
    assertThatThrownBy(() -> service.login(null, PASSWORD, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("priya@test.in", null, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("   ", PASSWORD, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    // blank password covers 4th short-circuit branch of the compound ||
    assertThatThrownBy(() -> service.login("priya@test.in", "   ", null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void validationErrorOnMalformedIdentifier() {
    assertThatThrownBy(() -> service.login("not-an-email", PASSWORD, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("bad@ ", PASSWORD, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login("+911234567890", PASSWORD, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void nullClientIpDefaultsToZeroAddress() {
    // covers the clientIp == null ? "0.0.0.0" : clientIp ternary in session save
    PharmacyLoginResult result = service.login("priya@test.in", PASSWORD, null, null, "ua");
    assertThat(result.accessToken()).isNotBlank();
    assertThat(sessionStore.saved).hasSize(1);
    assertThat(sessionStore.saved.get(0).ipAddress()).isEqualTo("0.0.0.0");
  }

  @Test
  void ipRateLimitEnforced() {
    // exhaust rate limit
    for (int i = 0; i < 10; i++) {
      try {
        service.login("priya@test.in", "wrong", null, "9.9.9.9", "ua");
      } catch (Exception ignored) {
      }
    }
    assertThatThrownBy(() -> service.login("priya@test.in", PASSWORD, null, "9.9.9.9", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void selectsRequestedPharmacy() {
    UUID p2 = Ids.newId();
    pharmacyStore.byId.put(
        p2, new PharmacyRecord(p2, "Second Pharmacy", null, "Bengaluru", "STARTER"));
    PharmacyAssignmentRecord a2 = assignment(staffId, p2, "pharmacist", "Second Pharmacy");
    assignmentStore.byStaffId.put(staffId, List.of(baseAssignment, a2));
    assignmentStore.byStaffAndPharmacy.put(staffId + ":" + p2, a2);

    PharmacyLoginResult result = service.login("priya@test.in", PASSWORD, p2, "1.1.1.1", "ua");
    assertThat(result.activePharmacy().id()).isEqualTo(p2);
    assertThat(result.roleInActivePharmacy()).isEqualTo("pharmacist");
  }

  @Test
  void pharmacyNotFoundAfterLoginReturns404() {
    // Remove pharmacy from store so lookup fails after login
    pharmacyStore.byId.clear();

    assertThatThrownBy(() -> service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void noAssignmentsReturns403() {
    assignmentStore.byStaffId.put(staffId, List.of());

    assertThatThrownBy(() -> service.login("priya@test.in", PASSWORD, null, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_ASSIGNED");
  }

  @Test
  void requestedPharmacyNotInListReturns403() {
    UUID unknownId = Ids.newId();
    assertThatThrownBy(() -> service.login("priya@test.in", PASSWORD, unknownId, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void sha256HexThrowsOnDigestError() throws Exception {
    // cover catch(NoSuchAlgorithmException) by injecting a failing factory
    PharmacyLoginService badDigestService =
        new PharmacyLoginService(
            staffStore,
            assignmentStore,
            pharmacyStore,
            sessionStore,
            auditStore,
            encoder,
            jwt,
            rateLimiter,
            clock,
            new java.security.SecureRandom(),
            () -> {
              throw new java.security.NoSuchAlgorithmException("test");
            });
    assertThatThrownBy(() -> badDigestService.sha256Hex("value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 not available");
  }

  // helper to catch throwable
  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private PharmacyStaffRecord staff(
      String email,
      String phone,
      String hash,
      int failed,
      Instant lockedUntil,
      Instant lastFailed) {
    return new PharmacyStaffRecord(
        staffId,
        "Priya",
        email,
        phone,
        hash,
        null,
        "ACTIVE",
        failed,
        lockedUntil,
        lastFailed,
        null,
        null,
        NOW,
        NOW);
  }

  private PharmacyAssignmentRecord assignment(
      UUID sId, UUID pId, String roleCode, String pharmacyName) {
    return new PharmacyAssignmentRecord(
        Ids.newId(), sId, pId, roleCode, true, NOW, null, pharmacyName);
  }

  // ---- fakes ----

  private static final class FakeStaffStore implements PharmacyStaffStore {
    final Map<String, PharmacyStaffRecord> byEmail = new HashMap<>();
    final Map<String, PharmacyStaffRecord> byPhone = new HashMap<>();
    final Map<UUID, PharmacyStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyStaffRecord> findByEmail(String e) {
      return Optional.ofNullable(byEmail.get(e));
    }

    @Override
    public Optional<PharmacyStaffRecord> findByPhone(String p) {
      return Optional.ofNullable(byPhone.get(p));
    }

    @Override
    public Optional<PharmacyStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public PharmacyStaffRecord save(PharmacyStaffRecord s) {
      if (s.email() != null) byEmail.put(s.email(), s);
      if (s.phone() != null) byPhone.put(s.phone(), s);
      byId.put(s.id(), s);
      return s;
    }
  }

  private static final class FakeAssignmentStore implements PharmacyAssignmentStore {
    final Map<UUID, List<PharmacyAssignmentRecord>> byStaffId = new HashMap<>();
    final Map<String, PharmacyAssignmentRecord> byStaffAndPharmacy = new HashMap<>();

    @Override
    public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId) {
      return byStaffId.getOrDefault(staffId, List.of());
    }

    @Override
    public Optional<PharmacyAssignmentRecord> findActive(UUID staffId, UUID pharmacyId) {
      return Optional.ofNullable(byStaffAndPharmacy.get(staffId + ":" + pharmacyId));
    }
  }

  private static final class FakePharmacyStore implements PharmacyStore {
    final Map<UUID, PharmacyRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }
  }

  private static final class FakeSessionStore implements AuthSessionStore {
    final List<AuthSessionRecord> saved = new ArrayList<>();

    @Override
    public AuthSessionRecord save(AuthSessionRecord s) {
      saved.add(s);
      return s;
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

  private static final class FakeAuditStore implements LoginAuditStore {
    final List<LoginAuditRecord> records = new ArrayList<>();

    @Override
    public void save(LoginAuditRecord r) {
      records.add(r);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant i) {
      this.instant = i;
    }

    void advance(Instant i) {
      this.instant = i;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId z) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
