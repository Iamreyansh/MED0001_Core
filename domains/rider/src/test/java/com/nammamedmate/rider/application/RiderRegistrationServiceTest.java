package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort.ZoneInfo;
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

class RiderRegistrationServiceTest {

  private FakeRiderStore store;
  private FakeZones zones;
  private RiderRegistrationService service;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    store = new FakeRiderStore();
    zones = new FakeZones();
    service = new RiderRegistrationService(store, zones, clock);
  }

  @Test
  void ac001_registerCreatesPendingKyc() {
    Map<String, Object> data =
        service.register(
            "Ravi Kumar", "9876543210", "ravi@example.com", "BIKE", "KA01AB1234", null);
    assertThat(data.get("status")).isEqualTo("PENDING_KYC");
    assertThat(data.get("kyc_status")).isEqualTo("NOT_SUBMITTED");
    assertThat(data.get("phone")).isEqualTo("+919876543210");
    assertThat(store.byPhone).containsKey("+919876543210");
  }

  @Test
  void ac002_duplicatePhoneConflict() {
    service.register("A", "9876543210", null, "BIKE", "KA01AB1234", null);
    assertThatThrownBy(
            () -> service.register("B", "+919876543210", null, "BIKE", "KA01AB1235", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHONE_ALREADY_REGISTERED");
  }

  @Test
  void ac003_invalidPlate() {
    assertThatThrownBy(() -> service.register("A", "9876543210", null, "BIKE", "KA-123", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_VEHICLE_PLATE");
  }

  @Test
  void invalidZone() {
    UUID zoneId = Ids.newId();
    assertThatThrownBy(
            () -> service.register("A", "9876543210", null, "BIKE", "KA01AB1234", zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
  }

  @Test
  void activeZoneAccepted() {
    UUID zoneId = Ids.newId();
    zones.zones.put(zoneId, new ZoneInfo(zoneId, "Indiranagar", true));
    Map<String, Object> data =
        service.register("A", "9876543211", "a@b.com", "SCOOTER", "MH12DE1234", zoneId);
    assertThat(data.get("rider_id")).isNotNull();
  }

  @Test
  void validationErrors() {
    assertThatThrownBy(() -> service.register("", "9876543210", null, "BIKE", "KA01AB1234", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.register("A", "123", null, "BIKE", "KA01AB1234", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.register("A", "9876543210", null, "TRUCK", "KA01AB1234", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.register("A", "9876543210", "x".repeat(300), "BIKE", "KA01AB1234", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  static final class FakeRiderStore implements RiderStore {
    final Map<String, RiderRecord> byPhone = new ConcurrentHashMap<>();
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byPhone.put(rider.phone(), rider);
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return Optional.ofNullable(byPhone.get(phone));
    }

    @Override
    public boolean existsByPhone(String phone) {
      return byPhone.containsKey(phone);
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
      byPhone.put(rider.phone(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      List<RiderRecord> all = new ArrayList<>(byId.values());
      return new PageResult(all, all.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
  }

  static final class FakeZones implements ZoneLookupPort {
    final Map<UUID, ZoneInfo> zones = new ConcurrentHashMap<>();

    @Override
    public Optional<ZoneInfo> findById(UUID zoneId) {
      return Optional.ofNullable(zones.get(zoneId));
    }
  }
}
