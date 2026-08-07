package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubCatalogueVisibilityClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyCatalogueStatsClient;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore;
import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore.CataloguePauseRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore.StorefrontRow;
import com.nammamedmate.pharmacy.application.port.out.ZonePharmacyCachePort;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.AdminZoneRow;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PharmacyStorefrontServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ZONE_A = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID ZONE_B = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OWNER = UUID.fromString("33333333-3333-4333-8333-333333333333");

  private FakeStorefrontStore storefront;
  private FakeZones zones;
  private FakeZoneCache zoneCache;
  private FakeAudit audit;
  private FakePauseStore pauseStore;
  private StubCatalogueVisibilityClient visibility;
  private RateLimiter rateLimiter;
  private PharmacyStorefrontService storefrontService;
  private CataloguePauseService pauseService;
  private AdminZoneService zoneService;

  @BeforeEach
  void setUp() {
    storefront = new FakeStorefrontStore();
    zones = new FakeZones();
    zoneCache = new FakeZoneCache();
    audit = new FakeAudit();
    pauseStore = new FakePauseStore();
    visibility = new StubCatalogueVisibilityClient(new StubPharmacyCatalogueStatsClient());
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    storefrontService =
        new PharmacyStorefrontService(storefront, zones, zoneCache, audit, rateLimiter, clock);
    pauseService =
        new CataloguePauseService(storefront, pauseStore, visibility, audit, rateLimiter, clock);
    zoneService = new AdminZoneService(zones, rateLimiter, 3);

    zones.put(ZONE_A, new ZoneRecord(ZONE_A, "Koramangala Zone", true));
    zones.put(ZONE_B, new ZoneRecord(ZONE_B, "Mumbai South Zone", true));
    storefront.put(activeStorefront(ZONE_A, true, false));
  }

  // AC1: admin offline sets flags and invalidates zone cache
  @Test
  void ac1_adminOfflineSetsFlagsAndInvalidatesCache() {
    Map<String, Object> data =
        storefrontService.adminToggleStorefront(
            admin(), PID, false, "Emergency closure", "127.0.0.1");

    assertThat(data.get("is_online")).isEqualTo(false);
    assertThat(data.get("admin_forced_offline")).isEqualTo(true);
    assertThat(data.get("cache_invalidated")).isEqualTo(true);
    assertThat(data.get("customer_app_reflects_change_in_seconds")).isEqualTo(5);
    assertThat(zoneCache.invalidated).contains(ZONE_A);

    StorefrontRow updated = storefront.get(PID);
    assertThat(updated.online()).isFalse();
    assertThat(updated.adminForcedOffline()).isTrue();
  }

  // AC2: owner cannot go online when admin override active
  @Test
  void ac2_ownerCannotOverrideAdminOffline() {
    storefront.put(activeStorefront(ZONE_A, false, true));

    assertThatThrownBy(() -> storefrontService.ownerToggleStorefront(owner(PID), true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADMIN_OVERRIDE_ACTIVE");
  }

  // AC3: zone reassignment updates zone, invalidates caches, audit log
  @Test
  void ac3_zoneReassignmentUpdatesZoneAndInvalidatesCaches() {
    Map<String, Object> data =
        storefrontService.reassignZone(admin(), PID, ZONE_B, null, "127.0.0.1");

    assertThat(storefront.get(PID).zoneId()).isEqualTo(ZONE_B);
    assertThat(data.get("cache_invalidation_triggered")).isEqualTo(true);
    assertThat(data.get("customer_app_reflects_change_in_minutes")).isEqualTo(5);
    assertThat(zoneCache.invalidated).contains(ZONE_A, ZONE_B);

    AuditLogRecord log =
        audit.entries.stream()
            .filter(e -> "ZONE_REASSIGNED".equals(e.action()))
            .findFirst()
            .orElseThrow();
    assertThat(log.payload().get("old_zone_id")).isEqualTo(ZONE_A.toString());
    assertThat(log.payload().get("new_zone_id")).isEqualTo(ZONE_B.toString());
    assertThat(log.payload().get("actor_id")).isEqualTo(ADMIN.toString());
  }

  // AC4: catalogue pause creates record and hides items
  @Test
  void ac4_cataloguePauseHidesItemsAndCreatesRecord() {
    Map<String, Object> data =
        pauseService.pauseCatalogue(admin(), PID, 120, "Inventory audit", "127.0.0.1");

    assertThat(data.get("catalogue_paused")).isEqualTo(true);
    assertThat(data.get("pause_reason")).isEqualTo("Inventory audit");
    assertThat(data.get("auto_resume_at")).isEqualTo(NOW.plus(Duration.ofHours(2)).toString());
    assertThat(data.get("items_hidden_count")).isEqualTo(100);
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(visibility.isHidden(PID)).isTrue();
    assertThat(pauseStore.active(PID)).isPresent();
  }

  // AC5: auto-resume restores visibility
  @Test
  void ac5_autoResumeRestoresVisibility() {
    pauseService.pauseCatalogue(admin(), PID, 120, "Audit", "127.0.0.1");
    CataloguePauseRow pause = pauseStore.active(PID).orElseThrow();
    pauseStore.due.add(
        new CataloguePauseRow(
            pause.id(),
            pause.pharmacyId(),
            pause.reason(),
            pause.pausedAt(),
            NOW.minusSeconds(1),
            null,
            pause.itemsHiddenCount(),
            pause.pausedBy()));

    int resumed = pauseService.resumeDuePauses();

    assertThat(resumed).isEqualTo(1);
    assertThat(visibility.isHidden(PID)).isFalse();
    assertThat(pauseStore.resumed).contains(pause.id());
  }

  // AC6: zone list returns counts and low-pharmacy warning
  @Test
  void ac6_zoneListReturnsCountsAndWarning() {
    zones.adminRows =
        List.of(
            new AdminZoneRow(
                ZONE_A,
                "Koramangala Zone",
                "Bengaluru",
                "Karnataka",
                true,
                12,
                9,
                new BigDecimal("8.40"),
                NOW),
            new AdminZoneRow(
                ZONE_B,
                "Whitefield Zone",
                "Bengaluru",
                "Karnataka",
                true,
                2,
                1,
                new BigDecimal("12.10"),
                NOW));

    AdminZoneService.ZoneListResult result = zoneService.list(supportAdmin(), null, true);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> zonesList = (List<Map<String, Object>>) result.data().get("zones");
    assertThat(zonesList).hasSize(2);
    assertThat(zonesList.get(0).get("pharmacy_count")).isEqualTo(12);
    assertThat(zonesList.get(0).get("online_pharmacy_count")).isEqualTo(9);
    assertThat(zonesList.get(0).get("has_low_pharmacy_warning")).isEqualTo(false);
    assertThat(zonesList.get(1).get("pharmacy_count")).isEqualTo(2);
    assertThat(zonesList.get(1).get("online_pharmacy_count")).isEqualTo(1);
    assertThat(zonesList.get(1).get("has_low_pharmacy_warning")).isEqualTo(true);
    assertThat(result.meta().total()).isEqualTo(2);
  }

  // AC7: suspended pharmacy returns PHARMACY_NOT_ACTIVE for admin and owner
  @Test
  void ac7_suspendedPharmacyStorefrontRejected() {
    storefront.put(new StorefrontRow(PID, "SUSPENDED", false, false, ZONE_A, "Koramangala Zone"));

    assertThatThrownBy(
            () -> storefrontService.adminToggleStorefront(admin(), PID, true, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code(), ex -> ((AppException) ex).httpStatus())
        .containsExactly("PHARMACY_NOT_ACTIVE", 409);

    assertThatThrownBy(() -> storefrontService.ownerToggleStorefront(owner(PID), true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code(), ex -> ((AppException) ex).httpStatus())
        .containsExactly("PHARMACY_NOT_ACTIVE", 403);
  }

  @Test
  void adminBringOnlineClearsForcedOffline() {
    storefront.put(activeStorefront(ZONE_A, false, true));

    Map<String, Object> data =
        storefrontService.adminToggleStorefront(admin(), PID, true, null, null);

    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(data.get("admin_forced_offline")).isEqualTo(false);
    assertThat(storefront.get(PID).adminForcedOffline()).isFalse();
  }

  @Test
  void zoneReassignAlreadyInZone() {
    assertThatThrownBy(() -> storefrontService.reassignZone(admin(), PID, ZONE_A, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_IN_ZONE");
  }

  @Test
  void cataloguePauseAlreadyPaused() {
    pauseService.pauseCatalogue(admin(), PID, 60, "first", null);
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 60, "second", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CATALOGUE_ALREADY_PAUSED");
  }

  @Test
  void cataloguePauseInvalidDuration() {
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 0, "reason", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DURATION");
  }

  @Test
  void cataloguePauseReasonRequired() {
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 60, "  ", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void ownerToggleOnlineSuccess() {
    Map<String, Object> data = storefrontService.ownerToggleStorefront(owner(PID), true);
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(zoneCache.invalidated).contains(ZONE_A);
  }

  @Test
  void schedulerDelegatesToService() {
    CataloguePauseService mockService = mock(CataloguePauseService.class);
    when(mockService.resumeDuePauses()).thenReturn(2);
    CataloguePauseResumeScheduler scheduler = new CataloguePauseResumeScheduler(mockService);
    scheduler.resumeExpiredPauses();
    org.mockito.Mockito.verify(mockService).resumeDuePauses();
  }

  private static StorefrontRow activeStorefront(
      UUID zoneId, boolean online, boolean adminForcedOffline) {
    return new StorefrontRow(PID, "ACTIVE", online, adminForcedOffline, zoneId, "Koramangala Zone");
  }

  private static MedmatePrincipal admin() {
    return new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private static MedmatePrincipal supportAdmin() {
    return new MedmatePrincipal(
        UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  private static MedmatePrincipal owner(UUID pharmacyId) {
    return new MedmatePrincipal(OWNER, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
  }

  static final class FakeStorefrontStore implements PharmacyStorefrontStore {
    final Map<UUID, StorefrontRow> rows = new ConcurrentHashMap<>();

    void put(StorefrontRow row) {
      rows.put(row.pharmacyId(), row);
    }

    StorefrontRow get(UUID id) {
      return rows.get(id);
    }

    @Override
    public Optional<StorefrontRow> findStorefront(UUID pharmacyId) {
      return Optional.ofNullable(rows.get(pharmacyId));
    }

    @Override
    public void updateOnlineStatus(
        UUID pharmacyId, boolean isOnline, boolean adminForcedOffline, Instant updatedAt) {
      StorefrontRow cur = rows.get(pharmacyId);
      if (cur != null) {
        rows.put(
            pharmacyId,
            new StorefrontRow(
                pharmacyId,
                cur.status(),
                isOnline,
                adminForcedOffline,
                cur.zoneId(),
                cur.zoneName()));
      }
    }

    @Override
    public void updateZone(UUID pharmacyId, UUID zoneId, Instant updatedAt) {
      StorefrontRow cur = rows.get(pharmacyId);
      if (cur != null) {
        rows.put(
            pharmacyId,
            new StorefrontRow(
                pharmacyId, cur.status(), cur.online(), cur.adminForcedOffline(), zoneId, null));
      }
    }
  }

  static final class FakeZones implements ZoneStore {
    final Map<UUID, ZoneRecord> byId = new ConcurrentHashMap<>();
    List<AdminZoneRow> adminRows = List.of();

    void put(UUID id, ZoneRecord record) {
      byId.put(id, record);
    }

    @Override
    public Optional<ZoneRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<AdminZoneRow> listForAdmin(String city, Boolean isActive) {
      return adminRows;
    }
  }

  static final class FakeZoneCache implements ZonePharmacyCachePort {
    final List<UUID> invalidated = new CopyOnWriteArrayList<>();

    @Override
    public void invalidate(UUID zoneId) {
      invalidated.add(zoneId);
    }
  }

  static final class FakeAudit implements AuditLogStore {
    final List<AuditLogRecord> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      entries.add(record);
    }
  }

  static final class FakePauseStore implements CataloguePauseStore {
    final Map<UUID, CataloguePauseRow> activeByPharmacy = new ConcurrentHashMap<>();
    final List<UUID> resumed = new CopyOnWriteArrayList<>();
    final List<CataloguePauseRow> due = new CopyOnWriteArrayList<>();

    Optional<CataloguePauseRow> active(UUID pharmacyId) {
      return Optional.ofNullable(activeByPharmacy.get(pharmacyId));
    }

    @Override
    public Optional<CataloguePauseRow> findActivePause(UUID pharmacyId) {
      return active(pharmacyId);
    }

    @Override
    public void insert(CataloguePauseRow row) {
      activeByPharmacy.put(row.pharmacyId(), row);
    }

    @Override
    public void markResumed(UUID id, Instant resumedAt) {
      resumed.add(id);
      activeByPharmacy.values().removeIf(r -> r.id().equals(id));
    }

    @Override
    public List<CataloguePauseRow> findDueForResume(Instant asOf) {
      return new ArrayList<>(due);
    }
  }
}
