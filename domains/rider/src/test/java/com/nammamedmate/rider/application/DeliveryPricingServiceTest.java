package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.application.DeliveryPricingService.LockedQuote;
import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore;
import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore.Snapshot;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort.AddressGeo;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort.PharmacyGeo;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.DemandHour;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneRow;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneSummaryRow;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.domain.DeliveryFeeFormula;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryPricingServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID PHARMACY = UUID.fromString("b0000001-0000-4000-8000-000000000001");
  private static final UUID ADDRESS = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  private FakeZones zones;
  private FakePricingConfig config;
  private FakeSnapshots snapshots;
  private FakeLookup lookup;
  private DistanceMatrixPort distance;
  private InMemoryRateLimiter limiter;
  private DeliveryPricingService service;

  @BeforeEach
  void setUp() {
    zones = new FakeZones();
    zones.put(
        new ZoneRow(
            ZONE,
            "Koramangala",
            "Bengaluru",
            "Karnataka",
            "{}",
            new BigDecimal("7.2"),
            new BigDecimal("25.00"),
            new BigDecimal("5.00"),
            30,
            new BigDecimal("50.00"),
            new BigDecimal("199.00"),
            new BigDecimal("1.50"),
            false,
            true,
            null,
            true,
            ADMIN,
            NOW,
            NOW));
    config = new FakePricingConfig();
    snapshots = new FakeSnapshots();
    lookup = new FakeLookup();
    lookup.pharmacies.put(
        PHARMACY, new PharmacyGeo(PHARMACY, "Apollo Pharmacy, Koramangala", 12.9350, 77.6245));
    lookup.addresses.put(ADDRESS, new AddressGeo(ADDRESS, 12.9300, 77.6200));
    distance =
        new DistanceMatrixPort() {
          @Override
          public double distanceKm(UUID riderId, Double pharmacyLat, Double pharmacyLng) {
            return 3.0;
          }

          @Override
          public RouteEstimate estimateDriving(
              double originLat, double originLng, double destLat, double destLng) {
            return new RouteEstimate(3.0, 25);
          }
        };
    limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new DeliveryPricingService(
            zones, config, snapshots, lookup, distance, limiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_feeEstimateThreeKmReturns40PlusHandling5() {
    Map<String, Object> data =
        service.feeEstimate("1.2.3.4", PHARMACY, null, 12.9300, 77.6200, new BigDecimal("100"));
    assertThat(data.get("delivery_fee")).isEqualTo(40.0);
    assertThat(data.get("handling_fee")).isEqualTo(5.0);
    assertThat(data.get("distance_km")).isEqualTo(3.0);
  }

  @Test
  void ac002_freeDeliveryWhenOrderValueAboveThreshold() {
    Map<String, Object> data =
        service.feeEstimate("1.2.3.4", PHARMACY, ADDRESS, null, null, new BigDecimal("250"));
    assertThat(data.get("is_free_delivery")).isEqualTo(true);
    assertThat(data.get("delivery_fee")).isEqualTo(0.0);
    assertThat(data.get("handling_fee")).isEqualTo(5.0);
  }

  @Test
  void ac003_surgeMultipliesDeliveryFee() {
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                z.minOrderValue(),
                z.freeDeliveryThreshold(),
                new BigDecimal("1.50"),
                true,
                z.serviceable(),
                z.offlineReason(),
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    Map<String, Object> data =
        service.feeEstimate("9.9.9.9", PHARMACY, null, 12.93, 77.62, new BigDecimal("50"));
    // (25 + 3*5) * 1.5 = 60
    assertThat(data.get("delivery_fee")).isEqualTo(60.0);
    assertThat(data.get("surge_multiplier")).isEqualTo(1.5);
  }

  @Test
  void ac004_simulatorRiderPayoutMaxFeeMinus070Or15() {
    Map<String, Object> data =
        service.simulate(admin(), ZONE, new BigDecimal("3.2"), new BigDecimal("150"));
    @SuppressWarnings("unchecked")
    Map<String, Object> breakdown = (Map<String, Object>) data.get("breakdown");
    assertThat(breakdown.get("delivery_fee")).isEqualTo(41.0);
    assertThat(data.get("rider_delivery_payout")).isEqualTo(40.3);
    assertThat(data.get("rider_payout_note").toString()).contains("0.70");
    assertThat(DeliveryFeeFormula.riderPayout(BigDecimal.ZERO)).isEqualByComparingTo("15.00");
  }

  @Test
  void ac005_patchPricingUpdatesSubsequentEstimates() {
    service.patchPricing(
        admin(),
        ZONE,
        new BigDecimal("30"),
        new BigDecimal("6"),
        25,
        new BigDecimal("60"),
        new BigDecimal("249"));
    Map<String, Object> data =
        service.feeEstimate("8.8.8.8", PHARMACY, null, 12.93, 77.62, new BigDecimal("50"));
    // 30 + 3*6 = 48
    assertThat(data.get("delivery_fee")).isEqualTo(48.0);
  }

  @Test
  void ac006_snapshotLockedAtPlacementUnaffectedBySurgeToggle() {
    LockedQuote quote =
        service.quoteForDelivery(PHARMACY, 12.93, 77.62, 10_000L, false).orElseThrow();
    UUID orderId = UUID.randomUUID();
    service.lockSnapshot(orderId, quote);
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                z.minOrderValue(),
                z.freeDeliveryThreshold(),
                new BigDecimal("2.00"),
                true,
                z.serviceable(),
                z.offlineReason(),
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    Snapshot snap = snapshots.findByOrderId(orderId).orElseThrow();
    assertThat(snap.deliveryFee()).isEqualByComparingTo(quote.deliveryFee());
    assertThat(snap.surgeMultiplier()).isEqualByComparingTo(quote.surgeMultiplier());
  }

  @Test
  void ac007_addressOutsidePolygonsNotServiceable() {
    zones.containing = Optional.empty();
    assertThatThrownBy(() -> service.feeEstimate("1.1.1.1", PHARMACY, null, 1.0, 1.0, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_NOT_SERVICEABLE");
  }

  @Test
  void ac008_rateLimitExceededAfter30() {
    for (int i = 0; i < 30; i++) {
      service.feeEstimate("10.0.0.1", PHARMACY, null, 12.93, 77.62, null);
    }
    assertThatThrownBy(() -> service.feeEstimate("10.0.0.1", PHARMACY, null, 12.93, 77.62, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void listPricingAndInactiveSurgeUsesEffectiveOne() {
    Map<String, Object> data = service.listPricing(admin());
    assertThat(data.get("handling_fee")).isEqualTo(5.0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> zoneList = (List<Map<String, Object>>) data.get("zones");
    assertThat(zoneList.getFirst().get("effective_surge")).isEqualTo(1.0);
    assertThat(zoneList.getFirst().get("sample_fee_2km")).isEqualTo(35.0);
    assertThat(zoneList.getFirst().get("sample_fee_5km")).isEqualTo(50.0);
  }

  @Test
  void validationErrorsAndZoneOfflineAndPharmacyMissing() {
    assertThatThrownBy(() -> service.simulate(admin(), null, new BigDecimal("1"), BigDecimal.ZERO))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ZONE_NOT_FOUND");
    assertThatThrownBy(() -> service.simulate(admin(), ZONE, BigDecimal.ZERO, BigDecimal.ZERO))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DISTANCE");
    assertThatThrownBy(
            () -> service.simulate(admin(), ZONE, new BigDecimal("1"), new BigDecimal("-1")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ORDER_VALUE");
    assertThatThrownBy(
            () -> service.patchPricing(admin(), ZONE, new BigDecimal("-1"), null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FEE");
    assertThatThrownBy(
            () ->
                service.patchPricing(
                    admin(), ZONE, null, null, null, new BigDecimal("100"), new BigDecimal("50")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_THRESHOLD");
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                z.minOrderValue(),
                z.freeDeliveryThreshold(),
                z.surgeMultiplier(),
                z.surgeActive(),
                false,
                "offline",
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    assertThatThrownBy(() -> service.feeEstimate("2.2.2.2", PHARMACY, null, 12.93, 77.62, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ZONE_OFFLINE");
    assertThatThrownBy(
            () -> service.feeEstimate("3.3.3.3", UUID.randomUUID(), null, 1.0, 1.0, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    assertThatThrownBy(
            () -> service.feeEstimate("3.3.3.3", PHARMACY, UUID.randomUUID(), null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_NOT_FOUND");
    assertThatThrownBy(() -> service.feeEstimate("3.3.3.3", PHARMACY, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listPricing(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void quoteFreedelAndLockSnapshotNoops() {
    LockedQuote quote =
        service.quoteForDelivery(PHARMACY, 12.93, 77.62, 10_000L, true).orElseThrow();
    assertThat(quote.deliveryFeePaise()).isZero();
    assertThat(quote.handlingFeePaise()).isEqualTo(500L);
    service.lockSnapshot(null, quote);
    service.lockSnapshot(UUID.randomUUID(), null);
    LockedQuote noZone =
        new LockedQuote(
            null,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            new BigDecimal("5"),
            true,
            new BigDecimal("15"),
            0L,
            500L);
    service.lockSnapshot(UUID.randomUUID(), noZone);
    assertThat(service.quoteForDelivery(null, 1, 1, 1, false)).isEmpty();
    assertThat(service.handlingFeeRupees()).isEqualByComparingTo("5.00");
    assertThat(
            new StubDistanceMatrixAdapter().estimateDriving(12.9, 77.6, 12.93, 77.62).distanceKm())
        .isPositive();
  }

  @Test
  void coverageGapsForNullBranchesAndQuoteFallbacks() {
    assertThatThrownBy(() -> service.simulate(admin(), ZONE, null, BigDecimal.ZERO))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DISTANCE");
    assertThatThrownBy(() -> service.simulate(admin(), ZONE, new BigDecimal("1"), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ORDER_VALUE");
    assertThatThrownBy(
            () -> service.patchPricing(admin(), ZONE, null, new BigDecimal("-1"), null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FEE");
    Map<String, Object> patched = service.patchPricing(admin(), ZONE, null, null, 28, null, null);
    assertThat(patched.get("sla_minutes")).isEqualTo(28);
    assertThatThrownBy(() -> service.feeEstimate(" ", null, null, 12.9, 77.6, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    assertThatThrownBy(
            () ->
                service.simulate(admin(), UUID.randomUUID(), new BigDecimal("1"), BigDecimal.ZERO))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ZONE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                service.listPricing(
                    new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, null, TokenScope.FULL, "j")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    lookup.pharmacies.clear();
    assertThat(service.quoteForDelivery(PHARMACY, 12.93, 77.62, 10_000L, false)).isEmpty();
    lookup.pharmacies.put(
        PHARMACY, new PharmacyGeo(PHARMACY, "Apollo Pharmacy, Koramangala", 12.9350, 77.6245));
    zones.containing = Optional.empty();
    assertThat(service.quoteForDelivery(PHARMACY, 12.93, 77.62, 10_000L, false)).isEmpty();
    zones.containing = null;
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                z.minOrderValue(),
                z.freeDeliveryThreshold(),
                z.surgeMultiplier(),
                z.surgeActive(),
                false,
                "offline",
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    assertThat(service.quoteForDelivery(PHARMACY, 12.93, 77.62, 10_000L, false)).isEmpty();
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                z.minOrderValue(),
                z.freeDeliveryThreshold(),
                z.surgeMultiplier(),
                z.surgeActive(),
                true,
                null,
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    LockedQuote freeQuote =
        service.quoteForDelivery(PHARMACY, 12.93, 77.62, 25_000L, false).orElseThrow();
    assertThat(freeQuote.freeDelivery()).isTrue();
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                null,
                null,
                z.surgeMultiplier(),
                z.surgeActive(),
                z.serviceable(),
                z.offlineReason(),
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    Map<String, Object> ok =
        service.patchPricing(admin(), ZONE, new BigDecimal("25"), null, null, null, null);
    assertThat(ok.get("base_fee")).isEqualTo(25.0);
    // nextFree set, nextMin null → threshold check short-circuits
    zones.byId.computeIfPresent(
        ZONE,
        (k, z) ->
            new ZoneRow(
                z.id(),
                z.name(),
                z.city(),
                z.state(),
                z.polygonGeoJson(),
                z.areaSqKm(),
                z.baseFee(),
                z.perKmFee(),
                z.slaMinutes(),
                null,
                new BigDecimal("199"),
                z.surgeMultiplier(),
                z.surgeActive(),
                z.serviceable(),
                z.offlineReason(),
                z.active(),
                z.createdBy(),
                z.createdAt(),
                z.updatedAt()));
    assertThat(
            service
                .patchPricing(admin(), ZONE, null, null, null, null, new BigDecimal("199"))
                .get("free_delivery_threshold"))
        .isEqualTo(199.0);
    assertThatThrownBy(() -> service.feeEstimate(null, PHARMACY, null, 12.93, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> withNullIp = service.feeEstimate(null, PHARMACY, null, 12.93, 77.62, null);
    assertThat(withNullIp.get("is_serviceable")).isEqualTo(true);
  }

  private static MedmatePrincipal admin() {
    return new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  static final class FakePricingConfig implements PlatformPricingConfigStore {
    @Override
    public Optional<String> get(String key) {
      return Optional.of("5.00");
    }

    @Override
    public BigDecimal handlingFeeRupees() {
      return new BigDecimal("5.00");
    }

    @Override
    public void upsert(String key, String value, String description, UUID updatedBy, Instant now) {}
  }

  static final class FakeSnapshots implements DeliveryFeeSnapshotStore {
    final Map<UUID, Snapshot> byOrder = new ConcurrentHashMap<>();

    @Override
    public void insert(Snapshot snapshot) {
      byOrder.put(snapshot.orderId(), snapshot);
    }

    @Override
    public Optional<Snapshot> findByOrderId(UUID orderId) {
      return Optional.ofNullable(byOrder.get(orderId));
    }
  }

  static final class FakeLookup implements DeliveryPricingLookupPort {
    final Map<UUID, PharmacyGeo> pharmacies = new ConcurrentHashMap<>();
    final Map<UUID, AddressGeo> addresses = new ConcurrentHashMap<>();

    @Override
    public Optional<PharmacyGeo> findPharmacy(UUID pharmacyId) {
      return Optional.ofNullable(pharmacies.get(pharmacyId));
    }

    @Override
    public Optional<AddressGeo> findAddress(UUID addressId) {
      return Optional.ofNullable(addresses.get(addressId));
    }
  }

  static final class FakeZones implements DeliveryZoneStore {
    final Map<UUID, ZoneRow> byId = new ConcurrentHashMap<>();
    Optional<ZoneRow> containing = null;

    void put(ZoneRow z) {
      byId.put(z.id(), z);
    }

    @Override
    public Optional<ZoneRow> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ZoneRow> listPricing() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<ZoneRow> findContaining(double lat, double lng) {
      if (containing != null) {
        return containing;
      }
      return byId.values().stream().findFirst();
    }

    @Override
    public boolean existsNameInCity(String name, String city, UUID excludeId) {
      return false;
    }

    @Override
    public List<ZoneSummaryRow> list(String city, Boolean serviceable, int offset, int limit) {
      return List.of();
    }

    @Override
    public int count(String city, Boolean serviceable) {
      return 0;
    }

    @Override
    public void insert(
        UUID id,
        String name,
        String city,
        String state,
        String wkt,
        String polygonGeoJson,
        BigDecimal areaSqKm,
        BigDecimal baseFee,
        BigDecimal perKmFee,
        int slaMinutes,
        BigDecimal minOrderValue,
        BigDecimal freeDeliveryThreshold,
        BigDecimal surgeMultiplier,
        boolean serviceable,
        UUID createdBy,
        Instant now) {}

    @Override
    public void updateFields(
        UUID id,
        Integer slaMinutes,
        BigDecimal baseFee,
        BigDecimal perKmFee,
        BigDecimal minOrderValue,
        BigDecimal freeDeliveryThreshold,
        String name,
        String wkt,
        String polygonGeoJson,
        BigDecimal areaSqKm,
        Instant now) {
      ZoneRow z = byId.get(id);
      if (z == null) {
        return;
      }
      byId.put(
          id,
          new ZoneRow(
              z.id(),
              name != null ? name : z.name(),
              z.city(),
              z.state(),
              z.polygonGeoJson(),
              z.areaSqKm(),
              baseFee != null ? baseFee : z.baseFee(),
              perKmFee != null ? perKmFee : z.perKmFee(),
              slaMinutes != null ? slaMinutes : z.slaMinutes(),
              minOrderValue != null ? minOrderValue : z.minOrderValue(),
              freeDeliveryThreshold != null ? freeDeliveryThreshold : z.freeDeliveryThreshold(),
              z.surgeMultiplier(),
              z.surgeActive(),
              z.serviceable(),
              z.offlineReason(),
              z.active(),
              z.createdBy(),
              z.createdAt(),
              now));
    }

    @Override
    public void updateSurge(
        UUID id, boolean surgeActive, BigDecimal surgeMultiplier, Instant now) {}

    @Override
    public void updateServiceable(UUID id, boolean serviceable, String reason, Instant now) {}

    @Override
    public int countServiceable() {
      return 1;
    }

    @Override
    public int countOnlineRiders(UUID zoneId) {
      return 0;
    }

    @Override
    public int countOnlineRidersAll() {
      return 0;
    }

    @Override
    public int countPharmacies(UUID zoneId) {
      return 0;
    }

    @Override
    public List<DemandHour> demandVsSupply(UUID zoneId, Instant from, Instant to) {
      return List.of();
    }

    @Override
    public BigDecimal avgDeliveryMinutes(UUID zoneId) {
      return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal avgDeliveryMinutesAll() {
      return BigDecimal.ZERO;
    }

    @Override
    public boolean isPharmacyAddressServiceable(UUID pharmacyId, double lat, double lng) {
      return true;
    }

    @Override
    public Optional<BigDecimal> minOrderValueForPharmacyAddress(
        UUID pharmacyId, double lat, double lng) {
      return Optional.empty();
    }
  }
}
