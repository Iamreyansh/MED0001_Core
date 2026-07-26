package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwitchPharmacyServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  private FakeAssignmentStore assignmentStore;
  private FakePharmacyStore pharmacyStore;
  private SwitchPharmacyService service;
  private Rs256JwtService jwtService;
  private InMemoryRateLimiter rateLimiter;
  private final UUID staffId = Ids.newId();
  private final UUID pharmacyId = Ids.newId();

  @BeforeEach
  void setUp() throws Exception {
    assignmentStore = new FakeAssignmentStore();
    pharmacyStore = new FakePharmacyStore();
    rateLimiter = new InMemoryRateLimiter(Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    Clock clock = Clock.systemUTC();
    jwtService =
        new Rs256JwtService(
            pair.getPrivate(),
            pair.getPublic(),
            new InMemoryTokenRevocationStore(clock),
            clock,
            900);

    service = new SwitchPharmacyService(assignmentStore, pharmacyStore, jwtService, rateLimiter);

    pharmacyStore.byId.put(
        pharmacyId,
        new PharmacyRecord(pharmacyId, "Sri Rama Medicals", null, "Bengaluru", "GROWTH"));
    assignmentStore.byStaffAndPharmacy.put(
        staffId + ":" + pharmacyId,
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, NOW, null, "Sri Rama Medicals"));
  }

  @Test
  void switchHappyPath() {
    SwitchPharmacyResult result = service.switchPharmacy(staffId, pharmacyId);

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.accessTtlSeconds()).isEqualTo(900L);
    assertThat(result.pharmacy().name()).isEqualTo("Sri Rama Medicals");
    assertThat(result.roleInPharmacy()).isEqualTo("owner");

    // Verify token has POS=FULL scope
    var parsed = jwtService.parseAndValidate(result.accessToken());
    assertThat(parsed.tokenScope()).isEqualTo(TokenScope.FULL);
    assertThat(parsed.pharmacyId()).isEqualTo(pharmacyId);
  }

  @Test
  void pharmacyNotFoundReturns404() {
    assertThatThrownBy(() -> service.switchPharmacy(staffId, Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void notAssignedReturns403() {
    UUID otherId = Ids.newId();
    pharmacyStore.byId.put(otherId, new PharmacyRecord(otherId, "Other", null, "Chennai", "FREE"));

    assertThatThrownBy(() -> service.switchPharmacy(staffId, otherId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void roleMapsToPHARMACY_STAFFForNonOwner() {
    UUID p2 = Ids.newId();
    pharmacyStore.byId.put(p2, new PharmacyRecord(p2, "P2", null, "Mumbai", "STARTER"));
    assignmentStore.byStaffAndPharmacy.put(
        staffId + ":" + p2,
        new PharmacyAssignmentRecord(Ids.newId(), staffId, p2, "cashier", true, NOW, null, "P2"));

    SwitchPharmacyResult result = service.switchPharmacy(staffId, p2);
    var parsed = jwtService.parseAndValidate(result.accessToken());
    assertThat(parsed.role().value()).isEqualTo("pharmacy_staff");
  }

  @Test
  void rateLimitEnforcedPerStaff() {
    for (int i = 0; i < 30; i++) {
      service.switchPharmacy(staffId, pharmacyId);
    }
    assertThatThrownBy(() -> service.switchPharmacy(staffId, pharmacyId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  // fakes
  private static final class FakeAssignmentStore implements PharmacyAssignmentStore {
    final Map<String, PharmacyAssignmentRecord> byStaffAndPharmacy = new HashMap<>();

    @Override
    public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId) {
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
}
