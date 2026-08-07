package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.adapter.out.cache.RedisZonePharmacyCacheClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubCatalogueVisibilityClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyCatalogueStatsClient;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontServiceTest.FakeAudit;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontServiceTest.FakePauseStore;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontServiceTest.FakeStorefrontStore;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontServiceTest.FakeZoneCache;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontServiceTest.FakeZones;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort.CatalogueStats;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore.StorefrontRow;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.AdminZoneRow;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class PharmacyStorefrontBranchCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ZONE_A = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID ZONE_B = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");

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
    storefront.put(active(ZONE_A, true, false));
  }

  @Test
  void authAndValidationBranches() {
    assertThatThrownBy(() -> storefrontService.adminToggleStorefront(null, PID, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal support =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> storefrontService.adminToggleStorefront(support, PID, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(
            () -> storefrontService.adminToggleStorefront(admin(), PID, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                storefrontService.adminToggleStorefront(admin(), PID, true, "x".repeat(501), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID missing = Ids.newId();
    assertThatThrownBy(
            () -> storefrontService.adminToggleStorefront(admin(), missing, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    assertThatThrownBy(() -> PharmacyStorefrontService.requirePharmacyRole(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(ADMIN, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> PharmacyStorefrontService.requirePharmacyRole(noPharmacy))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, PID, TokenScope.FULL, "j");
    assertThatThrownBy(() -> PharmacyStorefrontService.requirePharmacyRole(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> storefrontService.ownerToggleStorefront(owner(PID), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminToggleWithoutReasonAndOnlineAction() {
    Map<String, Object> data =
        storefrontService.adminToggleStorefront(admin(), PID, true, null, null);
    assertThat(data).doesNotContainKey("reason");
    assertThat(data.get("admin_forced_offline")).isEqualTo(false);

    storefrontService.adminToggleStorefront(admin(), PID, false, "  ", null);
    assertThat(audit.entries).isEmpty();
  }

  @Test
  void ownerCanGoOfflineDespiteAdminOverride() {
    storefront.put(active(ZONE_A, false, true));
    Map<String, Object> data = storefrontService.ownerToggleStorefront(owner(PID), false);
    assertThat(data.get("is_online")).isEqualTo(false);
  }

  @Test
  void zoneReassignBranches() {
    assertThatThrownBy(() -> storefrontService.reassignZone(admin(), PID, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");

    UUID badZone = Ids.newId();
    assertThatThrownBy(() -> storefrontService.reassignZone(admin(), PID, badZone, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");

    UUID inactiveId = Ids.newId();
    zones.put(inactiveId, new ZoneRecord(inactiveId, "Inactive", false));
    assertThatThrownBy(() -> storefrontService.reassignZone(admin(), PID, inactiveId, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");

    storefront.put(new StorefrontRow(PID, "ACTIVE", true, false, null, null));
    Map<String, Object> noOldZone = storefrontService.reassignZone(admin(), PID, ZONE_B, NOW, null);
    assertThat(noOldZone.get("previous_zone_id")).isNull();
    assertThat(noOldZone.get("previous_zone_name")).isNull();
  }

  @Test
  void rateLimitBranches() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(
            () -> storefrontService.adminToggleStorefront(admin(), PID, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> storefrontService.reassignZone(admin(), PID, ZONE_B, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> zoneService.list(admin(), null, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 60, "reason", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void adminZoneServiceBranches() {
    assertThatThrownBy(() -> zoneService.list(null, null, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> zoneService.list(customer, null, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    zones.adminRows =
        List.of(
            new AdminZoneRow(
                ZONE_A, "Z", "City", "State", true, 5, 3, new BigDecimal("1.0"), null));
    AdminZoneService.ZoneListResult result = zoneService.list(admin(), null, false);
    assertThat(result.data().get("zones")).isNotNull();
    assertThat(new AdminZoneService.ZoneListResult(null, null).data()).isEmpty();
  }

  @Test
  void cataloguePauseBranches() {
    assertThatThrownBy(() -> pauseService.pauseCatalogue(null, PID, 60, "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal support =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> pauseService.pauseCatalogue(support, PID, 60, "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    storefront.put(new StorefrontRow(PID, "SUSPENDED", false, false, ZONE_A, "z"));
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 60, "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_ACTIVE");

    storefront.put(active(ZONE_A, true, false));
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 2000, "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DURATION");

    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), PID, 60, "x".repeat(501), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThat(pauseService.resumeDuePauses()).isZero();
  }

  @Test
  void redisZoneCacheNullProviderAndBlankInvalidate() {
    RedisZonePharmacyCacheClient noProvider = new RedisZonePharmacyCacheClient(null);
    noProvider.invalidate(ZONE_A);
    assertThat(noProvider.wasInvalidatedLocally(ZONE_A)).isTrue();
  }

  @Test
  void cataloguePausePharmacyNotFound() {
    assertThatThrownBy(() -> pauseService.pauseCatalogue(admin(), Ids.newId(), 60, "reason", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void adminToggleOnlineWithReasonAudits() {
    Map<String, Object> data =
        storefrontService.adminToggleStorefront(admin(), PID, true, "back online", null);
    assertThat(data.get("reason")).isEqualTo("back online");
    assertThat(audit.entries.stream().anyMatch(e -> "STOREFRONT_ONLINE".equals(e.action())))
        .isTrue();
  }

  @Test
  void invalidateZoneCacheSkipsNullZone() {
    storefront.put(new StorefrontRow(PID, "ACTIVE", true, false, null, null));
    storefrontService.adminToggleStorefront(admin(), PID, false, null, null);
    assertThat(zoneCache.invalidated).isEmpty();
  }

  @Test
  void adminZoneComplianceRoleAllowed() {
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    zones.adminRows = List.of();
    assertThat(zoneService.list(compliance, "  ", null).data()).containsKey("zones");
  }

  @Test
  void redisZoneCacheUsesRedisWhenAvailable() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisZonePharmacyCacheClient client = new RedisZonePharmacyCacheClient(provider);
    client.invalidate(ZONE_A);
    verify(redis).delete(RedisZonePharmacyCacheClient.cacheKey(ZONE_A));
  }

  @Test
  void remainingBranchCoverage() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    storefrontService.adminToggleStorefront(superAdmin, PID, false, "offline", null);

    storefront.put(new StorefrontRow(PID, "ACTIVE", true, false, ZONE_A, "Koramangala Zone"));
    zones.byId.remove(ZONE_A);
    Map<String, Object> reassign =
        storefrontService.reassignZone(superAdmin, PID, ZONE_B, null, null);
    assertThat(reassign.get("previous_zone_id")).isEqualTo(ZONE_A.toString());
    assertThat(reassign.get("previous_zone_name")).isNull();

    assertThatThrownBy(() -> pauseService.pauseCatalogue(superAdmin, PID, null, "reason", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DURATION");

    assertThatThrownBy(() -> pauseService.pauseCatalogue(superAdmin, PID, 60, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");

    pauseService.pauseCatalogue(superAdmin, PID, 60, "ok", null);

    zones.adminRows = List.of();
    assertThat(zoneService.list(superAdmin, null, true).data()).containsKey("zones");
  }

  @Test
  void stubVisibilityUsesCatalogueCountWhenNonZero() {
    PharmacyCatalogueStatsPort stats = uuid -> new CatalogueStats(42, 10, 5);
    StubCatalogueVisibilityClient client = new StubCatalogueVisibilityClient(stats);
    assertThat(client.hideAll(PID)).isEqualTo(42);
  }

  private static StorefrontRow active(UUID zoneId, boolean online, boolean forced) {
    return new StorefrontRow(PID, "ACTIVE", online, forced, zoneId, "Koramangala Zone");
  }

  private static MedmatePrincipal admin() {
    return new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private static MedmatePrincipal owner(UUID pharmacyId) {
    return new MedmatePrincipal(
        UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
  }
}
