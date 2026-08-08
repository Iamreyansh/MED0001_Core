package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.CreateZoneCommand;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.ListResult;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.PatchZoneCommand;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminDeliveryZoneServiceTest {

  private static final UUID ADMIN = Ids.newId();
  private static final UUID ZONE_A = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID ZONE_B = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final Instant NOW = Instant.parse("2026-07-24T11:00:00Z");

  private FakeZones zones;
  private FakeSuggestions suggestions;
  private FakeFleet fleet;
  private FakeDeliveries deliveries;
  private InMemoryOutboxStore outbox;
  private AdminDeliveryZoneService service;

  @BeforeEach
  void setUp() {
    zones = new FakeZones();
    suggestions = new FakeSuggestions();
    fleet = new FakeFleet();
    deliveries = new FakeDeliveries();
    outbox = new InMemoryOutboxStore();
    service =
        new AdminDeliveryZoneService(
            zones,
            suggestions,
            fleet,
            deliveries,
            new OutboxPublisher(outbox, new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    zones.put(
        zone(
            ZONE_A,
            "Koramangala",
            "Bengaluru",
            true,
            false,
            new BigDecimal("25.00"),
            new BigDecimal("1.00")));
    zones.put(
        zone(
            ZONE_B,
            "Indiranagar",
            "Bengaluru",
            true,
            false,
            new BigDecimal("30.00"),
            new BigDecimal("1.00")));
    zones.onlineByZone.put(ZONE_A, 5);
    zones.onlineByZone.put(ZONE_B, 1);
    deliveries.liveByZone.put(ZONE_A, 2);
    deliveries.liveByZone.put(ZONE_B, 5);
  }

  @Test
  void ac001_createValidPolygon() {
    Map<String, Object> data =
        service.create(
            admin(),
            new CreateZoneCommand(
                "HSR Layout",
                "Bengaluru",
                null,
                polygon(),
                new BigDecimal("25.00"),
                new BigDecimal("5.00"),
                30,
                new BigDecimal("50.00"),
                new BigDecimal("199.00"),
                new BigDecimal("1.50"),
                true));
    assertThat(data.get("zone_id")).isNotNull();
    assertThat(data.get("area_sq_km")).isNotNull();
    assertThat(zones.byId).hasSize(3);
  }

  @Test
  void ac008_duplicateNameConflict() {
    assertThatThrownBy(
            () ->
                service.create(
                    admin(),
                    new CreateZoneCommand(
                        "Koramangala",
                        "Bengaluru",
                        null,
                        polygon(),
                        new BigDecimal("25"),
                        new BigDecimal("5"),
                        30,
                        null,
                        null,
                        null,
                        true)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NAME_DUPLICATE");
  }

  @Test
  void createInvalidPolygonAndFee() {
    assertThatThrownBy(
            () ->
                service.create(
                    admin(),
                    new CreateZoneCommand(
                        "X",
                        "Bengaluru",
                        null,
                        Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(1.0)))),
                        new BigDecimal("25"),
                        new BigDecimal("5"),
                        30,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin(),
                    new CreateZoneCommand(
                        "Y",
                        "Bengaluru",
                        null,
                        polygon(),
                        new BigDecimal("-1"),
                        new BigDecimal("5"),
                        30,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_FEE");
  }

  @Test
  void ac005_ac006_listSummaryAndUnderStrain() {
    zones.avgAll = new BigDecimal("19.4");
    ListResult result = service.list(admin(), "Bengaluru", true, 1, 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) result.data().get("summary_chips");
    assertThat(chips.get("serviceable_zones")).isEqualTo(2);
    assertThat(chips.get("zones_under_strain")).isEqualTo(1);
    assertThat(chips.get("avg_delivery_minutes")).isEqualTo(19.4);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.data().get("zones");
    assertThat(list)
        .anyMatch(
            z ->
                ZONE_B.toString().equals(z.get("zone_id"))
                    && "UNDER_STRAIN".equals(z.get("coverage_status")));
  }

  @Test
  void ac003_serviceableToggle() {
    UUID pharmacyId = Ids.newId();
    zones.pharmacyZone.put(pharmacyId, ZONE_A);
    int liveBefore = deliveries.liveByZone.get(ZONE_A);
    assertThat(zones.isPharmacyAddressServiceable(pharmacyId, 12.93, 77.62)).isTrue();

    Map<String, Object> data = service.setServiceable(admin(), ZONE_A, false, "Flooding in area");
    assertThat(data.get("is_serviceable")).isEqualTo(false);
    assertThat(zones.byId.get(ZONE_A).serviceable()).isFalse();
    // New checkouts blocked (zone membership gate); existing live orders untouched.
    assertThat(zones.isPharmacyAddressServiceable(pharmacyId, 12.93, 77.62)).isFalse();
    assertThat(deliveries.liveByZone.get(ZONE_A)).isEqualTo(liveBefore);
  }

  @Test
  void ac004_surgeAffectsFeeEstimate() {
    service.setSurge(admin(), ZONE_A, true, new BigDecimal("1.50"));
    ZoneRowView z = zones.byId.get(ZONE_A);
    BigDecimal fee =
        service.estimateDeliveryFee(
            new DeliveryZoneStore.ZoneRow(
                z.id,
                z.name,
                z.city,
                z.state,
                z.polygonGeoJson,
                z.areaSqKm,
                z.baseFee,
                z.perKmFee,
                z.slaMinutes,
                z.minOrderValue,
                z.freeDeliveryThreshold,
                z.surgeMultiplier,
                z.surgeActive,
                z.serviceable,
                z.offlineReason,
                z.active,
                z.createdBy,
                z.createdAt,
                z.updatedAt),
            2.0,
            new BigDecimal("100"));
    // (25 + 2*5) * 1.5 = 52.50 → nearest whole rupee 53.00 (STORY-006 BR-001)
    assertThat(fee).isEqualByComparingTo("53.00");
  }

  @Test
  void surgeValidationAndGetPatch() {
    assertThatThrownBy(() -> service.setSurge(admin(), ZONE_A, true, new BigDecimal("0.5")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MULTIPLIER");
    assertThatThrownBy(() -> service.setSurge(admin(), Ids.newId(), true, new BigDecimal("1.5")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");

    UUID riderId = Ids.newId();
    fleet
        .byZone
        .computeIfAbsent(ZONE_A, k -> new ArrayList<>())
        .add(
            new RiderFleetStore.FleetRiderRow(
                riderId,
                "Ravi",
                "+919876543210",
                ZONE_A,
                "Koramangala",
                "BIKE",
                "ONLINE",
                ZONE_A,
                NOW,
                new BigDecimal("4.7"),
                new BigDecimal("90"),
                0,
                0L));
    Map<String, Object> detail = service.get(admin(), ZONE_A);
    assertThat(detail.get("zone_name")).isEqualTo("Koramangala");
    assertThat(detail.get("riders")).asList().isNotEmpty();

    Map<String, Object> patched =
        service.patch(
            admin(),
            ZONE_A,
            new PatchZoneCommand(null, 25, null, new BigDecimal("6.00"), null, null, null));
    assertThat(patched.get("sla_minutes")).isEqualTo(25);
    assertThat(patched.get("per_km_fee")).isEqualTo(6.0);
  }

  @Test
  void ac007_applyRebalancingNotifiesRiders() {
    UUID riderId = Ids.newId();
    UUID rider2 = Ids.newId();
    fleet
        .byZone
        .computeIfAbsent(ZONE_A, k -> new ArrayList<>())
        .add(
            new RiderFleetStore.FleetRiderRow(
                riderId,
                "Ravi",
                "+919876543210",
                ZONE_A,
                "Koramangala",
                "BIKE",
                "ONLINE",
                ZONE_A,
                NOW,
                null,
                null,
                0,
                0L));
    fleet
        .byZone
        .get(ZONE_A)
        .add(
            new RiderFleetStore.FleetRiderRow(
                rider2,
                "Asha",
                "+919876543211",
                ZONE_A,
                "Koramangala",
                "BIKE",
                "ONLINE",
                ZONE_A,
                NOW,
                null,
                null,
                0,
                0L));
    fleet
        .byZone
        .get(ZONE_A)
        .add(
            new RiderFleetStore.FleetRiderRow(
                Ids.newId(),
                "Off",
                "+919876543212",
                ZONE_A,
                "Koramangala",
                "BIKE",
                "OFFLINE",
                ZONE_A,
                NOW,
                null,
                null,
                0,
                0L));
    Map<String, Object> sug = service.rebalancingSuggestions(admin());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) sug.get("suggestions");
    assertThat(list).isNotEmpty();
    UUID sid = UUID.fromString(list.get(0).get("suggestion_id").toString());
    Map<String, Object> applied = service.applyRebalancing(admin(), sid);
    assertThat(applied.get("applied")).isEqualTo(true);
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("rebalancing"));
    assertThatThrownBy(() -> service.applyRebalancing(admin(), sid))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_ALREADY_APPLIED");
  }

  @Test
  void demandVsSupplyAndForbidden() {
    zones.demandHours =
        List.of(
            new DeliveryZoneStore.DemandHour(
                NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS), 12, 5));
    Map<String, Object> chart = service.demandVsSupply(admin(), ZONE_A, null, null);
    assertThat(chart.get("zone_id")).isEqualTo(ZONE_A.toString());
    assertThat(chart.get("chart_data")).asList().isNotEmpty();

    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void moreBranchesPaginationPatchApplyEdges() {
    zones.onlineAll = 0;
    zones.avgAll = null;
    zones.onlineByZone.put(ZONE_B, 0);
    assertThat(service.list(admin(), null, null, null, null).meta().limit()).isEqualTo(20);
    service.list(admin(), null, null, 0, 0);
    service.list(admin(), null, null, 1, 200);
    service.create(
        admin(),
        new CreateZoneCommand(
            "Whitefield",
            "Bengaluru",
            "Karnataka",
            polygon(),
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    assertThatThrownBy(() -> service.demandVsSupply(admin(), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");
    assertThatThrownBy(() -> service.list(null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(
            () ->
                service.patch(
                    admin(),
                    ZONE_A,
                    new PatchZoneCommand(null, null, new BigDecimal("-1"), null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_FEE");
    assertThatThrownBy(
            () ->
                service.patch(
                    admin(),
                    ZONE_A,
                    new PatchZoneCommand(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("type", "Polygon", "coordinates", List.of()))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.patch(
                    admin(),
                    ZONE_A,
                    new PatchZoneCommand("Indiranagar", null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NAME_DUPLICATE");

    Map<String, Object> patched =
        service.patch(
            admin(),
            ZONE_A,
            new PatchZoneCommand(
                "Koramangala Central",
                null,
                new BigDecimal("26"),
                null,
                new BigDecimal("40"),
                new BigDecimal("250"),
                polygon()));
    assertThat(patched.get("zone_name")).isEqualTo("Koramangala Central");
    assertThat(patched.get("min_order_value")).isEqualTo(40.0);
    assertThat(patched.get("free_delivery_threshold")).isEqualTo(250.0);
    assertThat(patched.get("base_fee")).isEqualTo(26.0);

    assertThatThrownBy(() -> service.setSurge(admin(), ZONE_A, true, new BigDecimal("9")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MULTIPLIER");

    UUID sid = Ids.newId();
    suggestions.byId.put(
        sid,
        new RebalancingSuggestionStore.SuggestionRow(
            sid,
            ZONE_A,
            "Koramangala",
            ZONE_B,
            "Indiranagar",
            1,
            "r",
            new BigDecimal("80"),
            "[{\"name\":\"x\"}]",
            "DISMISSED",
            null,
            null,
            NOW.plusSeconds(100),
            NOW));
    assertThatThrownBy(() -> service.applyRebalancing(admin(), sid))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_NOT_FOUND");

    UUID expired = Ids.newId();
    suggestions.byId.put(
        expired,
        new RebalancingSuggestionStore.SuggestionRow(
            expired,
            ZONE_A,
            "Koramangala",
            ZONE_B,
            "Indiranagar",
            1,
            "r",
            new BigDecimal("80"),
            "[]",
            "PENDING",
            null,
            null,
            NOW,
            NOW));
    assertThatThrownBy(() -> service.applyRebalancing(admin(), expired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_NOT_FOUND");

    UUID race = Ids.newId();
    suggestions.byId.put(
        race,
        new RebalancingSuggestionStore.SuggestionRow(
            race,
            ZONE_A,
            "Koramangala",
            ZONE_B,
            "Indiranagar",
            1,
            "r",
            new BigDecimal("80"),
            "[]",
            "PENDING",
            null,
            null,
            NOW.plusSeconds(1000),
            NOW));
    suggestions.failMark = true;
    assertThatThrownBy(() -> service.applyRebalancing(admin(), race))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_ALREADY_APPLIED");
    suggestions.failMark = false;

    UUID skipRider = Ids.newId();
    suggestions.byId.put(
        skipRider,
        new RebalancingSuggestionStore.SuggestionRow(
            skipRider,
            ZONE_A,
            "Koramangala",
            ZONE_B,
            "Indiranagar",
            1,
            "r",
            new BigDecimal("80"),
            "[{\"rider_id\":null},{\"name\":\"x\"}]",
            "PENDING",
            null,
            null,
            NOW.plusSeconds(1000),
            NOW));
    assertThat(service.applyRebalancing(admin(), skipRider).get("riders_notified")).isEqualTo(2);

    // bad json riders still applies
    UUID badJson = Ids.newId();
    suggestions.byId.put(
        badJson,
        new RebalancingSuggestionStore.SuggestionRow(
            badJson,
            ZONE_A,
            "Koramangala",
            ZONE_B,
            "Indiranagar",
            1,
            "r",
            null,
            "not-json",
            "PENDING",
            null,
            null,
            NOW.plusSeconds(1000),
            NOW));
    assertThat(service.applyRebalancing(admin(), badJson).get("riders_notified")).isEqualTo(0);

    zones.byId.put(
        ZONE_A,
        zone(
            ZONE_A,
            "Koramangala",
            "Bengaluru",
            true,
            false,
            new BigDecimal("25"),
            new BigDecimal("1")));
    zones.byId.get(ZONE_A);
    // force blank geojson parse path via get
    ZoneRowView z = zones.byId.get(ZONE_A);
    zones.byId.put(
        ZONE_A,
        new ZoneRowView(
            z.id(),
            z.name(),
            z.city(),
            z.state(),
            "not-json",
            z.areaSqKm(),
            z.baseFee(),
            z.perKmFee(),
            z.slaMinutes(),
            z.minOrderValue(),
            z.freeDeliveryThreshold(),
            z.surgeMultiplier(),
            z.surgeActive(),
            z.serviceable(),
            z.offlineReason(),
            z.active(),
            z.createdBy(),
            z.createdAt(),
            z.updatedAt()));
    assertThat(service.get(admin(), ZONE_A).get("polygon")).isInstanceOf(Map.class);

    zones.byId.put(
        ZONE_A,
        new ZoneRowView(
            z.id(),
            z.name(),
            z.city(),
            z.state(),
            null,
            z.areaSqKm(),
            z.baseFee(),
            z.perKmFee(),
            z.slaMinutes(),
            z.minOrderValue(),
            z.freeDeliveryThreshold(),
            z.surgeMultiplier(),
            z.surgeActive(),
            z.serviceable(),
            z.offlineReason(),
            z.active(),
            z.createdBy(),
            z.createdAt(),
            z.updatedAt()));
    assertThat(service.get(admin(), ZONE_A).get("polygon")).isInstanceOf(Map.class);
  }

  private MedmatePrincipal admin() {
    return new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private static Map<String, Object> polygon() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "Polygon");
    p.put(
        "coordinates",
        List.of(
            List.of(
                List.of(77.61, 12.92),
                List.of(77.64, 12.92),
                List.of(77.64, 12.945),
                List.of(77.61, 12.945),
                List.of(77.61, 12.92))));
    return p;
  }

  private ZoneRowView zone(
      UUID id,
      String name,
      String city,
      boolean serviceable,
      boolean surge,
      BigDecimal base,
      BigDecimal surgeMult) {
    return new ZoneRowView(
        id,
        name,
        city,
        "Karnataka",
        "{\"type\":\"Polygon\",\"coordinates\":[[[77.61,12.92],[77.64,12.92],[77.64,12.945],[77.61,12.945],[77.61,12.92]]]}",
        new BigDecimal("7.200"),
        base,
        new BigDecimal("5.00"),
        30,
        new BigDecimal("50.00"),
        new BigDecimal("199.00"),
        surgeMult,
        surge,
        serviceable,
        null,
        serviceable,
        ADMIN,
        NOW,
        NOW);
  }

  record ZoneRowView(
      UUID id,
      String name,
      String city,
      String state,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      int slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      boolean surgeActive,
      boolean serviceable,
      String offlineReason,
      boolean active,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  static final class FakeZones implements DeliveryZoneStore {
    final Map<UUID, ZoneRowView> byId = new ConcurrentHashMap<>();
    final Map<UUID, Integer> onlineByZone = new ConcurrentHashMap<>();
    final Map<UUID, UUID> pharmacyZone = new ConcurrentHashMap<>();
    List<DemandHour> demandHours = List.of();
    int onlineAll = 5;
    BigDecimal avgAll = new BigDecimal("19.4");

    void put(ZoneRowView z) {
      byId.put(z.id, z);
    }

    private ZoneRow toRow(ZoneRowView z) {
      return new ZoneRow(
          z.id,
          z.name,
          z.city,
          z.state,
          z.polygonGeoJson,
          z.areaSqKm,
          z.baseFee,
          z.perKmFee,
          z.slaMinutes,
          z.minOrderValue,
          z.freeDeliveryThreshold,
          z.surgeMultiplier,
          z.surgeActive,
          z.serviceable,
          z.offlineReason,
          z.active,
          z.createdBy,
          z.createdAt,
          z.updatedAt);
    }

    @Override
    public Optional<ZoneRow> findById(UUID id) {
      return Optional.ofNullable(byId.get(id)).map(this::toRow);
    }

    @Override
    public List<ZoneRow> listPricing() {
      return byId.values().stream().map(this::toRow).toList();
    }

    @Override
    public Optional<ZoneRow> findContaining(double lat, double lng) {
      return byId.values().stream().findFirst().map(this::toRow);
    }

    @Override
    public boolean existsNameInCity(String name, String city, UUID excludeId) {
      return byId.values().stream()
          .anyMatch(
              z ->
                  z.name.equalsIgnoreCase(name)
                      && z.city.equalsIgnoreCase(city)
                      && (excludeId == null || !z.id.equals(excludeId)));
    }

    @Override
    public List<ZoneSummaryRow> list(String city, Boolean serviceable, int offset, int limit) {
      return byId.values().stream()
          .filter(z -> city == null || z.city.equalsIgnoreCase(city))
          .filter(z -> serviceable == null || z.serviceable == serviceable)
          .skip(offset)
          .limit(limit)
          .map(
              z ->
                  new ZoneSummaryRow(
                      z.id,
                      z.name,
                      z.city,
                      z.baseFee,
                      z.slaMinutes,
                      z.surgeMultiplier,
                      z.surgeActive,
                      z.serviceable,
                      1))
          .toList();
    }

    @Override
    public int count(String city, Boolean serviceable) {
      return list(city, serviceable, 0, 1000).size();
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
        Instant now) {
      byId.put(
          id,
          new ZoneRowView(
              id,
              name,
              city,
              state,
              polygonGeoJson,
              areaSqKm,
              baseFee,
              perKmFee,
              slaMinutes,
              minOrderValue,
              freeDeliveryThreshold,
              surgeMultiplier,
              false,
              serviceable,
              null,
              serviceable,
              createdBy,
              now,
              now));
    }

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
      ZoneRowView z = byId.get(id);
      byId.put(
          id,
          new ZoneRowView(
              z.id,
              name == null ? z.name : name,
              z.city,
              z.state,
              polygonGeoJson == null ? z.polygonGeoJson : polygonGeoJson,
              areaSqKm == null ? z.areaSqKm : areaSqKm,
              baseFee == null ? z.baseFee : baseFee,
              perKmFee == null ? z.perKmFee : perKmFee,
              slaMinutes == null ? z.slaMinutes : slaMinutes,
              minOrderValue == null ? z.minOrderValue : minOrderValue,
              freeDeliveryThreshold == null ? z.freeDeliveryThreshold : freeDeliveryThreshold,
              z.surgeMultiplier,
              z.surgeActive,
              z.serviceable,
              z.offlineReason,
              z.active,
              z.createdBy,
              z.createdAt,
              now));
    }

    @Override
    public void updateSurge(UUID id, boolean surgeActive, BigDecimal surgeMultiplier, Instant now) {
      ZoneRowView z = byId.get(id);
      byId.put(
          id,
          new ZoneRowView(
              z.id,
              z.name,
              z.city,
              z.state,
              z.polygonGeoJson,
              z.areaSqKm,
              z.baseFee,
              z.perKmFee,
              z.slaMinutes,
              z.minOrderValue,
              z.freeDeliveryThreshold,
              surgeMultiplier,
              surgeActive,
              z.serviceable,
              z.offlineReason,
              z.active,
              z.createdBy,
              z.createdAt,
              now));
    }

    @Override
    public void updateServiceable(UUID id, boolean serviceable, String reason, Instant now) {
      ZoneRowView z = byId.get(id);
      byId.put(
          id,
          new ZoneRowView(
              z.id,
              z.name,
              z.city,
              z.state,
              z.polygonGeoJson,
              z.areaSqKm,
              z.baseFee,
              z.perKmFee,
              z.slaMinutes,
              z.minOrderValue,
              z.freeDeliveryThreshold,
              z.surgeMultiplier,
              z.surgeActive,
              serviceable,
              reason,
              serviceable,
              z.createdBy,
              z.createdAt,
              now));
    }

    @Override
    public int countServiceable() {
      return (int) byId.values().stream().filter(z -> z.serviceable).count();
    }

    @Override
    public int countOnlineRiders(UUID zoneId) {
      return onlineByZone.getOrDefault(zoneId, 0);
    }

    @Override
    public int countOnlineRidersAll() {
      return onlineAll;
    }

    @Override
    public int countPharmacies(UUID zoneId) {
      return 1;
    }

    @Override
    public List<DemandHour> demandVsSupply(UUID zoneId, Instant from, Instant to) {
      return demandHours;
    }

    @Override
    public BigDecimal avgDeliveryMinutes(UUID zoneId) {
      return new BigDecimal("19.4");
    }

    @Override
    public BigDecimal avgDeliveryMinutesAll() {
      return avgAll;
    }

    @Override
    public boolean isPharmacyAddressServiceable(UUID pharmacyId, double lat, double lng) {
      UUID zoneId = pharmacyZone.get(pharmacyId);
      if (zoneId == null) {
        return true;
      }
      ZoneRowView z = byId.get(zoneId);
      return z != null && z.serviceable;
    }

    @Override
    public Optional<BigDecimal> minOrderValueForPharmacyAddress(
        UUID pharmacyId, double lat, double lng) {
      return Optional.of(new BigDecimal("50"));
    }
  }

  static final class FakeSuggestions implements RebalancingSuggestionStore {
    final Map<UUID, SuggestionRow> byId = new ConcurrentHashMap<>();
    boolean failMark;

    @Override
    public void insert(
        UUID id,
        UUID fromZoneId,
        UUID toZoneId,
        int ridersToMove,
        String reason,
        BigDecimal confidencePct,
        String suggestedRidersJson,
        Instant expiresAt,
        Instant generatedAt) {
      byId.put(
          id,
          new SuggestionRow(
              id,
              fromZoneId,
              "Koramangala",
              toZoneId,
              "Indiranagar",
              ridersToMove,
              reason,
              confidencePct,
              suggestedRidersJson,
              "PENDING",
              null,
              null,
              expiresAt,
              generatedAt));
    }

    @Override
    public List<SuggestionRow> listPending(Instant now) {
      return byId.values().stream()
          .filter(s -> "PENDING".equals(s.status()) && s.expiresAt().isAfter(now))
          .toList();
    }

    @Override
    public Optional<SuggestionRow> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt) {
      if (failMark) {
        return false;
      }
      SuggestionRow s = byId.get(id);
      if (s == null || !"PENDING".equals(s.status())) {
        return false;
      }
      byId.put(
          id,
          new SuggestionRow(
              s.id(),
              s.fromZoneId(),
              s.fromZoneName(),
              s.toZoneId(),
              s.toZoneName(),
              s.ridersToMove(),
              s.reason(),
              s.confidencePct(),
              s.suggestedRidersJson(),
              "APPLIED",
              appliedBy,
              appliedAt,
              s.expiresAt(),
              s.generatedAt()));
      return true;
    }

    @Override
    public void expireStale(Instant now) {}
  }

  static final class FakeFleet implements RiderFleetStore {
    final Map<UUID, List<FleetRiderRow>> byZone = new ConcurrentHashMap<>();

    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(List.of(), 0);
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return byZone.getOrDefault(zoneId, List.of());
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 12;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0;
    }
  }

  static final class FakeDeliveries implements ActiveDeliveryPort {
    final Map<UUID, Integer> liveByZone = new ConcurrentHashMap<>();
    final Map<UUID, ActiveOrder> byRider = new ConcurrentHashMap<>();

    @Override
    public Optional<ActiveOrder> findActiveByRider(UUID riderId) {
      return Optional.ofNullable(byRider.get(riderId));
    }

    @Override
    public int countLiveOrdersInZone(UUID zoneId) {
      return liveByZone.getOrDefault(zoneId, 0);
    }

    @Override
    public void flagForMonitoring(UUID orderId, String reason) {}
  }
}
