package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.nammamedmate.security.TokenScope;
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

class PosPinLoginServiceTest {

  private static final String PIN = "1234";
  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final MutableClock clock = new MutableClock(NOW);
  private FakeStaffStore staffStore;
  private FakeAssignmentStore assignmentStore;
  private FakePharmacyStore pharmacyStore;
  private FakeAuditStore auditStore;
  private InMemoryRateLimiter rateLimiter;
  private PosPinLoginService service;
  private Rs256JwtService jwtService;

  private final UUID pharmacyId = Ids.newId();
  private final UUID staffId = Ids.newId();
  private PharmacyStaffRecord baseStaff;
  private PharmacyAssignmentRecord baseAssignment;

  @BeforeEach
  void setUp() throws Exception {
    staffStore = new FakeStaffStore();
    assignmentStore = new FakeAssignmentStore();
    pharmacyStore = new FakePharmacyStore();
    auditStore = new FakeAuditStore();
    rateLimiter = new InMemoryRateLimiter(clock);

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    Clock systemClock = Clock.systemUTC();
    jwtService =
        new Rs256JwtService(
            pair.getPrivate(),
            pair.getPublic(),
            new InMemoryTokenRevocationStore(systemClock),
            systemClock,
            900);

    service =
        new PosPinLoginService(
            staffStore,
            assignmentStore,
            pharmacyStore,
            auditStore,
            encoder,
            jwtService,
            rateLimiter,
            clock);

    baseStaff =
        new PharmacyStaffRecord(
            staffId,
            "Kavya",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            encoder.encode(PIN),
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, baseStaff);

    baseAssignment =
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "cashier", true, NOW, null, "Sri Rama Medicals");
    assignmentStore.byStaffAndPharmacy.put(staffId + ":" + pharmacyId, baseAssignment);
    pharmacyStore.byId.put(
        pharmacyId,
        new PharmacyRecord(pharmacyId, "Sri Rama Medicals", null, "Bengaluru", "GROWTH"));
  }

  @Test
  void posPinHappyPath() {
    PosPinLoginResult result = service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua");

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.accessTtlSeconds()).isEqualTo(14400L);
    assertThat(result.roleInPharmacy()).isEqualTo("cashier");
    assertThat(result.pharmacy().name()).isEqualTo("Sri Rama Medicals");

    var parsed = jwtService.parseAndValidate(result.accessToken());
    assertThat(parsed.tokenScope()).isEqualTo(TokenScope.POS);
    assertThat(parsed.pharmacyId()).isEqualTo(pharmacyId);
  }

  @Test
  void staffNotFoundReturns404() {
    assertThatThrownBy(() -> service.login(pharmacyId, Ids.newId(), PIN, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void notAssignedReturns403() {
    UUID otherId = Ids.newId();
    pharmacyStore.byId.put(otherId, new PharmacyRecord(otherId, "Other", null, "X", "FREE"));
    assertThatThrownBy(() -> service.login(otherId, staffId, PIN, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_ASSIGNED");
  }

  @Test
  void posPinNotSetReturns403() {
    PharmacyStaffRecord noPin =
        new PharmacyStaffRecord(
            staffId,
            "Kavya",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, noPin);

    assertThatThrownBy(() -> service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("POS_PIN_NOT_SET");
  }

  @Test
  void invalidPinReturns401() {
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "0000", "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PIN");
    assertThat(auditStore.records).isNotEmpty();
    LoginAuditRecord audit = auditStore.records.get(auditStore.records.size() - 1);
    assertThat(audit.success()).isFalse();
    assertThat(audit.failureReason()).isEqualTo("INVALID_PIN");
    assertThat(audit.ipAddress()).isEqualTo("1.1.1.1");
    assertThat(audit.userAgent()).isEqualTo("ua");
  }

  @Test
  void suspendedStaffCannotPosLogin() {
    PharmacyStaffRecord suspended =
        new PharmacyStaffRecord(
            staffId,
            "Kavya",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            encoder.encode(PIN),
            "SUSPENDED",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, suspended);

    assertThatThrownBy(() -> service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_SUSPENDED");
  }

  @Test
  void invitedStaffCannotPosLogin() {
    PharmacyStaffRecord invited =
        new PharmacyStaffRecord(
            staffId,
            "Kavya",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            encoder.encode(PIN),
            "INVITED",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, invited);

    assertThatThrownBy(() -> service.login(pharmacyId, staffId, PIN, "2.2.2.2", "ua-invited"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(auditStore.records.get(0).ipAddress()).isEqualTo("2.2.2.2");
    assertThat(auditStore.records.get(0).userAgent()).isEqualTo("ua-invited");
  }

  @Test
  void fiveFailuresLockAccount() {
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login(pharmacyId, staffId, "0000", "1.1.1.1", "ua"))
          .extracting(e -> ((AppException) e).code())
          .isEqualTo("INVALID_PIN");
    }
    AppException ex =
        (AppException)
            catchThrowable(() -> service.login(pharmacyId, staffId, "0000", "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(ex.details()).containsKey("unlock_at");
  }

  @Test
  void lockedAccountCheckedBeforePinLookup() {
    PharmacyStaffRecord locked =
        new PharmacyStaffRecord(
            staffId,
            "K",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            encoder.encode(PIN),
            "ACTIVE",
            5,
            NOW.plusSeconds(1800),
            NOW.minusSeconds(10),
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, locked);

    AppException ex =
        (AppException)
            catchThrowable(() -> service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua"));
    assertThat(ex.code()).isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  void expiredLockAllowsPinLoginAndWrongPinDoesNotReThrowLocked() {
    PharmacyStaffRecord expired =
        new PharmacyStaffRecord(
            staffId,
            "K",
            null,
            "+919876543210",
            encoder.encode("Passw0rd!"),
            encoder.encode(PIN),
            "ACTIVE",
            2,
            NOW.minusSeconds(60),
            NOW.minusSeconds(120),
            null,
            null,
            NOW,
            NOW);
    staffStore.byId.put(staffId, expired);

    assertThat(service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua").accessToken()).isNotBlank();

    staffStore.byId.put(staffId, expired);
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "9999", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PIN");
  }

  @Test
  void validationRejectsNonFourDigitPin() {
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "123", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "12345", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, null, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.login(null, staffId, PIN, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void pharmacyNotFoundInStoreReturns404() {
    UUID unknownPharmacy = Ids.newId();
    assertThatThrownBy(() -> service.login(unknownPharmacy, staffId, PIN, "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void ipRateLimitEnforcedForPin() {
    // exhaust rate limit (20 per 60s) — ignore INVALID_PIN / ACCOUNT_LOCKED from wrong pin
    for (int i = 0; i < 20; i++) {
      try {
        service.login(pharmacyId, staffId, "0000", "8.8.8.8", "ua");
      } catch (AppException ignored) {
      }
    }
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, PIN, "8.8.8.8", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void pharmacyOwnerRoleIssuedInToken() {
    PharmacyAssignmentRecord ownerAssignment =
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, NOW, null, "Sri Rama Medicals");
    assignmentStore.byStaffAndPharmacy.put(staffId + ":" + pharmacyId, ownerAssignment);

    PosPinLoginResult result = service.login(pharmacyId, staffId, PIN, "1.1.1.1", "ua");
    var parsed = jwtService.parseAndValidate(result.accessToken());
    assertThat(parsed.role()).isEqualTo(com.nammamedmate.security.AuthRole.PHARMACY_OWNER);
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private static final class FakeStaffStore implements PharmacyStaffStore {
    final Map<UUID, PharmacyStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyStaffRecord> findByEmail(String e) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyStaffRecord> findByPhone(String p) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public PharmacyStaffRecord save(PharmacyStaffRecord s) {
      byId.put(s.id(), s);
      return s;
    }
  }

  private static final class FakeAssignmentStore implements PharmacyAssignmentStore {
    final Map<String, PharmacyAssignmentRecord> byStaffAndPharmacy = new HashMap<>();

    @Override
    public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID sId) {
      return List.of();
    }

    @Override
    public Optional<PharmacyAssignmentRecord> findActive(UUID sId, UUID pId) {
      return Optional.ofNullable(byStaffAndPharmacy.get(sId + ":" + pId));
    }
  }

  private static final class FakePharmacyStore implements PharmacyStore {
    final Map<UUID, PharmacyRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }
  }

  private static final class FakeAuditStore implements LoginAuditStore {
    final List<LoginAuditRecord> records = new ArrayList<>();

    @Override
    public void save(LoginAuditRecord r) {
      records.add(r);
    }
  }

  @Test
  void staffIdNullReturnsValidationError() {
    assertThatThrownBy(() -> service.login(pharmacyId, null, PIN, "1.1.1.1", "ua"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void pinFailureWindowResetAfterTenMinutes() {
    // first failure (lastFailedAt=null → new window, attempts=1)
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "0000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PIN");

    // advance clock past 10-minute window
    clock.advance(NOW.plus(11, java.time.temporal.ChronoUnit.MINUTES));

    // second failure after window → resets to attempts=1 (not 2)
    assertThatThrownBy(() -> service.login(pharmacyId, staffId, "0000", "1.1.1.1", "ua"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PIN");
    assertThat(staffStore.byId.get(staffId).failedLoginAttempts()).isEqualTo(1);
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
