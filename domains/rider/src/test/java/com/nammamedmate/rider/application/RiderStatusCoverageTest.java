package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLiveStatusCache;
import com.nammamedmate.rider.application.RiderStatusServiceTest.FakeAudits;
import com.nammamedmate.rider.application.RiderStatusServiceTest.FakeDeliveries;
import com.nammamedmate.rider.application.RiderStatusServiceTest.FakeFleet;
import com.nammamedmate.rider.application.RiderStatusServiceTest.FakeRiders;
import com.nammamedmate.rider.application.RiderStatusServiceTest.FakeShifts;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.RiderShiftStore.ShiftRecord;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore.AuditRecord;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.RiderAvailability;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Extra branches for JaCoCo 100% on status/shift paths. */
class RiderStatusCoverageTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:00:00Z"), ZoneOffset.UTC);
  private final UUID riderId = Ids.newId();
  private final UUID zoneId = Ids.newId();
  private FakeRiders riders;
  private FakeShifts shifts;
  private FakeAudits audits;
  private FakeDeliveries deliveries;
  private RiderStatusService service;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    shifts = new FakeShifts();
    audits = new FakeAudits();
    deliveries = new FakeDeliveries();
    riders.insert(sample("ACTIVE", "APPROVED", zoneId));
    service =
        new RiderStatusService(
            riders,
            shifts,
            audits,
            z ->
                z.equals(zoneId)
                    ? Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Koramangala", true))
                    : Optional.empty(),
            deliveries,
            new RedisRiderLiveStatusCache(null),
            new FakeFleet(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
  }

  @Test
  void alreadyOnlineIdempotentAndReopenAfterOffline() {
    service.setStatus(p(), "ONLINE", zoneId);
    var again = service.setStatus(p(), "ONLINE", zoneId);
    assertThat(again.get("message")).asString().contains("Already");
    service.setStatus(p(), "OFFLINE", null);
    // reopen: close path when status not ONLINE but open shift left — force open remnant
    shifts.insert(
        new ShiftRecord(
            Ids.newId(),
            riderId,
            zoneId,
            clock.instant().minusSeconds(60),
            null,
            null,
            0,
            0L,
            null,
            clock.instant()));
    riders.updateAvailability(riderId, "OFFLINE", zoneId, clock.instant());
    service.setStatus(p(), "ONLINE", zoneId);
    assertThat(riders.findById(riderId).orElseThrow().status()).isEqualTo("ONLINE");
  }

  @Test
  void nullZoneOnRiderAndForceReasonVisible() {
    riders.update(sample("ACTIVE", "APPROVED", null));
    assertThatThrownBy(() -> service.setStatus(p(), "ONLINE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
    riders.update(sample("ACTIVE", "APPROVED", zoneId));
    service.setStatus(p(), "ONLINE", zoneId);
    audits.insert(
        new AuditRecord(
            Ids.newId(),
            riderId,
            Ids.newId(),
            "admin_operations",
            "ONLINE",
            "OFFLINE",
            "unresponsive",
            clock.instant()));
    var status = service.getStatus(p());
    assertThat(status.get("force_status_reason")).isEqualTo("unresponsive");
  }

  @Test
  void getStatusWithoutOpenShiftAndNonOutForDeliveryOrder() {
    deliveries.active.set(new ActiveOrder(Ids.newId(), "READY_FOR_PICKUP", "HSR", 3));
    var status = service.getStatus(p());
    assertThat(status.get("status")).isEqualTo("ON_TRIP");
    @SuppressWarnings("unchecked")
    var ao = (java.util.Map<String, Object>) status.get("active_order");
    assertThat(ao.get("order_status")).isEqualTo("READY_FOR_PICKUP");
  }

  @Test
  void nullZoneIdInStatusResponses() {
    riders.update(sample("OFFLINE", "APPROVED", null));
    var status = service.getStatus(p());
    assertThat(status.get("zone_id")).isNull();
    var offline = service.setStatus(p(), "OFFLINE", null);
    assertThat(offline.get("zone_id")).isNull();
  }

  @Test
  void offlineWithoutOpenShiftAndMissingRider() {
    assertThat(service.setStatus(p(), "OFFLINE", null).get("status")).isEqualTo("OFFLINE");
    riders.byId.clear();
    assertThatThrownBy(() -> service.setStatus(p(), "ONLINE", zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.setStatus(p(), "OFFLINE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.getStatus(p()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
  }

  @Test
  void nullStatusAndNullPrincipal() {
    assertThatThrownBy(() -> service.setStatus(p(), null, zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThatThrownBy(() -> service.setStatus(null, "ONLINE", zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void redisCachePathsAndAvailabilityBranches() {
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(provider.getIfAvailable()).thenReturn(template);
    when(template.opsForValue()).thenReturn(ops);
    when(ops.get(org.mockito.ArgumentMatchers.anyString())).thenReturn("ONLINE");
    RedisRiderLiveStatusCache cache = new RedisRiderLiveStatusCache(provider);
    UUID id = Ids.newId();
    cache.put(id, "ONLINE", Duration.ofMinutes(1));
    assertThat(cache.get(id)).contains("ONLINE");
    cache.evict(id);

    RedisRiderLiveStatusCache local = new RedisRiderLiveStatusCache(null);
    local.put(id, "X", Duration.ofMillis(1));
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(local.get(id)).isEmpty();

    assertThat(RiderAvailability.canGoOnline("PENDING_KYC", "APPROVED")).isFalse();
    assertThat(RiderAvailability.displayStatus("ON_TRIP", false)).isEqualTo("ONLINE");
    assertThat(RiderAvailability.isOnlineForCoverage("OFFLINE")).isFalse();
  }

  private MedmatePrincipal p() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private RiderRecord sample(String status, String kyc, UUID primaryZone) {
    Instant now = clock.instant();
    return new RiderRecord(
        riderId,
        "Ravi",
        "+919876543210",
        null,
        "BIKE",
        "KA01AB1234",
        primaryZone,
        status,
        kyc,
        now,
        null,
        null,
        null,
        null,
        false,
        null,
        0,
        null,
        0L,
        0L,
        0,
        null,
        null,
        null,
        now,
        now);
  }
}
