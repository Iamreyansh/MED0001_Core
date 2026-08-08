package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.CreateZoneCommand;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.PatchZoneCommand;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Extra branches for AdminDeliveryZoneService JaCoCo. */
class AdminDeliveryZoneGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final UUID ADMIN = Ids.newId();
  private static final UUID Z1 = Ids.newId();
  private static final UUID Z2 = Ids.newId();

  @Test
  void edgeBranches() {
    DeliveryZoneStore zones = new MinimalZones();
    RebalancingSuggestionStore suggestions = new MinimalSuggestions();
    RiderFleetStore fleet = new EmptyFleet();
    ActiveDeliveryPort deliveries =
        new ActiveDeliveryPort() {
          @Override
          public Optional<ActiveDeliveryPort.ActiveOrder> findActiveByRider(UUID riderId) {
            return Optional.empty();
          }

          @Override
          public int countLiveOrdersInZone(UUID zoneId) {
            return zoneId.equals(Z2) ? 9 : 0;
          }

          @Override
          public void flagForMonitoring(UUID orderId, String reason) {}
        };
    AdminDeliveryZoneService service =
        new AdminDeliveryZoneService(
            zones,
            suggestions,
            fleet,
            deliveries,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    MedmatePrincipal admin =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.create(admin, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setSurge(admin, Z1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setServiceable(admin, Z1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patch(admin, Z1, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.applyRebalancing(admin, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_NOT_FOUND");

    // no ONLINE riders → stub suggestions empty
    assertThat(service.rebalancingSuggestions(admin).get("suggestions")).asList().isEmpty();

    // patch with polygon + rename Mumbai default state on create
    Map<String, Object> poly =
        Map.of(
            "type",
            "Polygon",
            "coordinates",
            List.of(
                List.of(
                    List.of(72.82, 18.95),
                    List.of(72.86, 18.95),
                    List.of(72.86, 18.98),
                    List.of(72.82, 18.98),
                    List.of(72.82, 18.95))));
    service.create(
        admin,
        new CreateZoneCommand(
            "Colaba", "Mumbai", null, poly, null, null, null, null, null, null, null));
    service.patch(
        admin,
        Z1,
        new PatchZoneCommand("Koramangala East", 20, new BigDecimal("26"), null, null, null, poly));
    service.setServiceable(admin, Z1, true, null);
    service.setSurge(admin, Z1, false, null);
    service.demandVsSupply(admin, Z1, LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-24"));

    // spare with offline-only riders → empty suggested → no insert
    RiderFleetStore offlineFleet =
        new RiderFleetStore() {
          @Override
          public FleetPage listFleet(FleetFilter filter) {
            return new FleetPage(List.of(), 0);
          }

          @Override
          public List<FleetRiderRow> listByZone(UUID zoneId) {
            return List.of(
                new FleetRiderRow(
                    Ids.newId(),
                    "Off",
                    "+919876543210",
                    zoneId,
                    "Z",
                    "BIKE",
                    "OFFLINE",
                    zoneId,
                    NOW,
                    null,
                    null,
                    0,
                    0L));
          }

          @Override
          public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
            return Optional.empty();
          }

          @Override
          public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
            return 0;
          }

          @Override
          public long sumShiftEarningsTodayPaise(
              UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
            return 0;
          }
        };
    AdminDeliveryZoneService withOffline =
        new AdminDeliveryZoneService(
            zones,
            suggestions,
            offlineFleet,
            deliveries,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(withOffline.rebalancingSuggestions(admin).get("suggestions")).asList().isEmpty();

    ObjectMapper bad =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("serialize fail");
          }
        };
    AdminDeliveryZoneService badJson =
        new AdminDeliveryZoneService(
            zones,
            suggestions,
            fleet,
            deliveries,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            bad,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                badJson.create(
                    admin,
                    new CreateZoneCommand(
                        "FailPoly",
                        "Bengaluru",
                        null,
                        Map.of(
                            "type",
                            "Polygon",
                            "coordinates",
                            List.of(
                                List.of(
                                    List.of(77.61, 12.92),
                                    List.of(77.64, 12.92),
                                    List.of(77.64, 12.945),
                                    List.of(77.61, 12.945),
                                    List.of(77.61, 12.92)))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "NoCity", "", null, poly, null, null, null, null, null, null, true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "", "Bengaluru", null, poly, null, null, null, null, null, null, true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "EmptyCoords",
                        "Bengaluru",
                        null,
                        Map.of("type", "Polygon", "coordinates", List.of()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "NonListPoint",
                        "Bengaluru",
                        null,
                        Map.of(
                            "type",
                            "Polygon",
                            "coordinates",
                            List.of(List.of("not-a-pair", List.of(1.0, 2.0)))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "NoPoly",
                        "Bengaluru",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "BadFee",
                        "Bengaluru",
                        null,
                        poly,
                        new BigDecimal("1"),
                        new BigDecimal("-1"),
                        null,
                        null,
                        null,
                        null,
                        false)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_FEE");
    assertThatThrownBy(
            () ->
                service.patch(
                    admin,
                    Z1,
                    new PatchZoneCommand(null, null, null, new BigDecimal("-2"), null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_FEE");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "BadRing",
                        "Bengaluru",
                        null,
                        Map.of("type", "Polygon", "coordinates", "nope"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "BadOuter",
                        "Bengaluru",
                        null,
                        Map.of("type", "Polygon", "coordinates", List.of("nope")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateZoneCommand(
                        "ShortPair",
                        "Bengaluru",
                        null,
                        Map.of(
                            "type",
                            "Polygon",
                            "coordinates",
                            List.of(List.of(List.of(1.0), List.of(2.0, 3.0)))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");

    // pending list with null confidence + blank riders json
    UUID sid = Ids.newId();
    RebalancingSuggestionStore seeded =
        new RebalancingSuggestionStore() {
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
              Instant generatedAt) {}

          @Override
          public List<SuggestionRow> listPending(Instant now) {
            return List.of(
                new SuggestionRow(
                    sid,
                    Z1,
                    "A",
                    Z2,
                    "B",
                    1,
                    "r",
                    null,
                    "",
                    "PENDING",
                    null,
                    null,
                    now.plusSeconds(60),
                    now));
          }

          @Override
          public Optional<SuggestionRow> findById(UUID id) {
            return Optional.empty();
          }

          @Override
          public boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt) {
            return false;
          }

          @Override
          public void expireStale(Instant now) {}
        };
    AdminDeliveryZoneService withPending =
        new AdminDeliveryZoneService(
            zones,
            seeded,
            fleet,
            deliveries,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pending =
        (List<Map<String, Object>>) withPending.rebalancingSuggestions(admin).get("suggestions");
    assertThat(pending.get(0).get("confidence_pct")).isEqualTo(0.0);

    // APPLIED status path
    RebalancingSuggestionStore appliedStore =
        new RebalancingSuggestionStore() {
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
              Instant generatedAt) {}

          @Override
          public List<SuggestionRow> listPending(Instant now) {
            return List.of();
          }

          @Override
          public Optional<SuggestionRow> findById(UUID id) {
            return Optional.of(
                new SuggestionRow(
                    sid,
                    Z1,
                    "A",
                    Z2,
                    "B",
                    1,
                    "r",
                    BigDecimal.ONE,
                    "[]",
                    "APPLIED",
                    ADMIN,
                    NOW,
                    NOW.plusSeconds(1),
                    NOW));
          }

          @Override
          public boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt) {
            return false;
          }

          @Override
          public void expireStale(Instant now) {}
        };
    AdminDeliveryZoneService withApplied =
        new AdminDeliveryZoneService(
            zones,
            appliedStore,
            fleet,
            deliveries,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> withApplied.applyRebalancing(admin, sid))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUGGESTION_ALREADY_APPLIED");

    ActiveDeliveryPort noLive =
        new ActiveDeliveryPort() {
          @Override
          public Optional<ActiveDeliveryPort.ActiveOrder> findActiveByRider(UUID riderId) {
            return Optional.empty();
          }

          @Override
          public int countLiveOrdersInZone(UUID zoneId) {
            return 0;
          }

          @Override
          public void flagForMonitoring(UUID orderId, String reason) {}
        };
    AdminDeliveryZoneService calm =
        new AdminDeliveryZoneService(
            zones,
            suggestions,
            fleet,
            noLive,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(calm.rebalancingSuggestions(admin).get("suggestions")).asList().isEmpty();

    assertThatThrownBy(() -> service.create(admin, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.create(
        admin,
        new CreateZoneCommand(
            "OfflineZone",
            "Bengaluru",
            null,
            poly,
            new BigDecimal("10"),
            new BigDecimal("2"),
            20,
            new BigDecimal("10"),
            new BigDecimal("100"),
            new BigDecimal("1.2"),
            false));
    service.patch(admin, Z1, new PatchZoneCommand("", 22, null, null, null, null, null));
    service.create(
        admin,
        new CreateZoneCommand(
            "StringCoords",
            "Bengaluru",
            null,
            Map.of(
                "type",
                "Polygon",
                "coordinates",
                List.of(
                    List.of(
                        List.of("77.61", "12.92"),
                        List.of("77.64", "12.92"),
                        List.of("77.64", "12.945"),
                        List.of("77.61", "12.945"),
                        List.of("77.61", "12.92")))),
            null,
            null,
            null,
            null,
            null,
            null,
            true));

    // both zones under strain → no spare
    ActiveDeliveryPort heavy =
        new ActiveDeliveryPort() {
          @Override
          public Optional<ActiveDeliveryPort.ActiveOrder> findActiveByRider(UUID riderId) {
            return Optional.empty();
          }

          @Override
          public int countLiveOrdersInZone(UUID zoneId) {
            return 9;
          }

          @Override
          public void flagForMonitoring(UUID orderId, String reason) {}
        };
    MinimalZones lowOnline = new MinimalZones();
    lowOnline.online = 1;
    AdminDeliveryZoneService strainedOnly =
        new AdminDeliveryZoneService(
            lowOnline,
            suggestions,
            fleet,
            heavy,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(strainedOnly.rebalancingSuggestions(admin).get("suggestions")).asList().isEmpty();
  }

  static class MinimalZones implements DeliveryZoneStore {
    int online = 5;

    @Override
    public Optional<ZoneRow> findById(UUID id) {
      if (id == null || (!id.equals(Z1) && !id.equals(Z2))) {
        // allow newly created ids after insert — store last
        return Optional.of(row(id == null ? Z1 : id, "Z", "Bengaluru"));
      }
      return Optional.of(row(id, id.equals(Z1) ? "Koramangala" : "Indiranagar", "Bengaluru"));
    }

    private ZoneRow row(UUID id, String name, String city) {
      return new ZoneRow(
          id,
          name,
          city,
          "Karnataka",
          "{\"type\":\"Polygon\",\"coordinates\":[]}",
          BigDecimal.ONE,
          new BigDecimal("25"),
          new BigDecimal("5"),
          30,
          BigDecimal.ZERO,
          new BigDecimal("199"),
          BigDecimal.ONE,
          false,
          true,
          null,
          true,
          ADMIN,
          NOW,
          NOW);
    }

    @Override
    public List<ZoneRow> listPricing() {
      return list(null, null, 0, 100).stream().map(s -> row(s.id(), s.name(), s.city())).toList();
    }

    @Override
    public Optional<ZoneRow> findContaining(double lat, double lng) {
      return Optional.of(row(Z1, "Koramangala", "Bengaluru"));
    }

    @Override
    public boolean existsNameInCity(String name, String city, UUID excludeId) {
      return false;
    }

    @Override
    public List<ZoneSummaryRow> list(String city, Boolean serviceable, int offset, int limit) {
      return List.of(
          new ZoneSummaryRow(
              Z1,
              "Koramangala",
              "Bengaluru",
              new BigDecimal("25"),
              30,
              BigDecimal.ONE,
              false,
              true,
              1),
          new ZoneSummaryRow(
              Z2,
              "Indiranagar",
              "Bengaluru",
              new BigDecimal("25"),
              30,
              BigDecimal.ONE,
              false,
              true,
              1));
    }

    @Override
    public int count(String city, Boolean serviceable) {
      return 2;
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
        Instant now) {}

    @Override
    public void updateSurge(
        UUID id, boolean surgeActive, BigDecimal surgeMultiplier, Instant now) {}

    @Override
    public void updateServiceable(UUID id, boolean serviceable, String reason, Instant now) {}

    @Override
    public int countServiceable() {
      return 2;
    }

    @Override
    public int countOnlineRiders(UUID zoneId) {
      return online > 0 ? online : (zoneId.equals(Z1) ? 5 : 1);
    }

    @Override
    public int countOnlineRidersAll() {
      return 6;
    }

    @Override
    public int countPharmacies(UUID zoneId) {
      return 0;
    }

    @Override
    public List<DemandHour> demandVsSupply(UUID zoneId, Instant from, Instant to) {
      return List.of(new DemandHour(NOW, 0, 0), new DemandHour(NOW.plusSeconds(3600), 3, 0));
    }

    @Override
    public BigDecimal avgDeliveryMinutes(UUID zoneId) {
      return null;
    }

    @Override
    public BigDecimal avgDeliveryMinutesAll() {
      return null;
    }

    @Override
    public boolean isPharmacyAddressServiceable(UUID pharmacyId, double lat, double lng) {
      return false;
    }

    @Override
    public Optional<BigDecimal> minOrderValueForPharmacyAddress(
        UUID pharmacyId, double lat, double lng) {
      return Optional.empty();
    }
  }

  static final class MinimalSuggestions implements RebalancingSuggestionStore {
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
        Instant generatedAt) {}

    @Override
    public List<SuggestionRow> listPending(Instant now) {
      return List.of();
    }

    @Override
    public Optional<SuggestionRow> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt) {
      return false;
    }

    @Override
    public void expireStale(Instant now) {}
  }

  static final class EmptyFleet implements RiderFleetStore {
    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(List.of(), 0);
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return List.of();
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0;
    }
  }
}
