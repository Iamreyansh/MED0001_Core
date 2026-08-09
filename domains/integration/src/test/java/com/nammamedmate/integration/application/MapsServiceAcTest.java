package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.adapter.out.client.StubMapsClient;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng;
import com.nammamedmate.integration.domain.MapsApiTypes;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MapsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private InMemoryStores.MapsLogs logs;
  private InMemoryStores.GeocodeCache cache;
  private List<String> events;
  private AtomicInteger googleCalls;
  private MapsClientPort client;
  private MapsService service;

  @BeforeEach
  void setUp() {
    logs = new InMemoryStores.MapsLogs();
    cache = new InMemoryStores.GeocodeCache();
    events = new ArrayList<>();
    googleCalls = new AtomicInteger();
    StubMapsClient stub = new StubMapsClient();
    client =
        new MapsClientPort() {
          @Override
          public GeocodeResult geocode(String addressQuery) {
            googleCalls.incrementAndGet();
            return stub.geocode(addressQuery);
          }

          @Override
          public ReverseGeocodeResult reverseGeocode(double lat, double lng) {
            googleCalls.incrementAndGet();
            return stub.reverseGeocode(lat, lng);
          }

          @Override
          public List<MatrixCell> distanceMatrix(
              List<LatLng> origins, List<LatLng> destinations, String mode) {
            googleCalls.incrementAndGet();
            return stub.distanceMatrix(origins, destinations, mode);
          }

          @Override
          public DirectionsResult directions(LatLng origin, LatLng destination, String mode) {
            googleCalls.incrementAndGet();
            return stub.directions(origin, destination, mode);
          }
        };
    service =
        new MapsService(
            client,
            logs,
            cache,
            (type, agg, id, payload) -> events.add(type),
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
  }

  @Test
  void ac001_regeocodeIsCacheHitWithoutGoogle() {
    Map<String, Object> first =
        service.geocode("12 Indiranagar", "Bangalore", "560038", "order_management");
    assertThat(first.get("cache_hit")).isEqualTo(false);
    assertThat(googleCalls.get()).isEqualTo(1);

    Map<String, Object> second =
        service.geocode("12 Indiranagar", "Bangalore", "560038", "order_management");
    assertThat(second.get("cache_hit")).isEqualTo(true);
    assertThat(googleCalls.get()).isEqualTo(1);
    assertThat(logs.all().get(1).wasCacheHit()).isTrue();
  }

  @Test
  void ac002_zoneCheckRayCastingNoGoogleFast() {
    Instant start = Instant.now();
    double[][] polygon = {
      {12.96, 77.62}, {12.96, 77.66}, {12.99, 77.66}, {12.99, 77.62}, {12.96, 77.62}
    };
    Map<String, Object> inside = service.zoneCheck(12.9716, 77.6412, polygon, "zone-1", "dispatch");
    Map<String, Object> outside = service.zoneCheck(13.5, 78.0, polygon, "zone-1", "dispatch");
    assertThat(inside.get("inside")).isEqualTo(true);
    assertThat(outside.get("inside")).isEqualTo(false);
    assertThat(googleCalls.get()).isZero();
    assertThat(Duration.between(start, Instant.now()).toMillis()).isLessThan(10_000);
    assertThat(logs.all()).allMatch(l -> MapsApiTypes.ZONE_CHECK.equals(l.apiType()));
  }

  @Test
  void ac003_tooManyOrigins() {
    List<LatLng> origins = new ArrayList<>();
    for (int i = 0; i < 26; i++) {
      origins.add(new LatLng(12.97 + i * 0.001, 77.64));
    }
    assertThatThrownBy(
            () ->
                service.distanceMatrix(
                    origins, List.of(new LatLng(12.98, 77.65)), "DRIVING", "dispatch"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_ORIGINS");
  }

  @Test
  void ac004_budgetExceededFiresAlert() {
    MapsClientPort costly =
        new MapsClientPort() {
          @Override
          public GeocodeResult geocode(String q) {
            return new GeocodeResult(12.97, 77.59, "a", "p", "ROOFTOP", "OK");
          }

          @Override
          public ReverseGeocodeResult reverseGeocode(double lat, double lng) {
            return new ReverseGeocodeResult("a", "l", "c", "s", "1", "p", "OK");
          }

          @Override
          public List<MatrixCell> distanceMatrix(
              List<LatLng> origins, List<LatLng> destinations, String mode) {
            return List.of(new MatrixCell(0, 0, 1000, 100, "OK"));
          }

          @Override
          public DirectionsResult directions(LatLng o, LatLng d, String mode) {
            return new DirectionsResult("poly", 100, 60, 80, List.of(), "OK");
          }
        };
    // Seed spend just under budget, then one geocode (0.42) pushes over if we seed 500.
    logs.insert(
        new com.nammamedmate.integration.domain.MapsApiCallLog(
            java.util.UUID.randomUUID(),
            MapsApiTypes.DIRECTIONS,
            "seed",
            "OK",
            1,
            false,
            new BigDecimal("500.0000"),
            NOW,
            "seed"));
    MapsService s =
        new MapsService(
            costly,
            logs,
            cache,
            (type, agg, id, payload) -> events.add(type),
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    s.geocode("Indiranagar", "Bangalore", "560038", "order");
    assertThat(events).contains("MAPS_BUDGET_EXCEEDED");
    s.geocode("MG Road", "Bangalore", "560001", "order");
    assertThat(events.stream().filter("MAPS_BUDGET_EXCEEDED"::equals).count()).isEqualTo(1);
  }

  @Test
  void ac005_zeroResults() {
    MapsService zero =
        new MapsService(
            new StubMapsClient(true, false),
            logs,
            cache,
            (t, a, i, p) -> {},
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    assertThatThrownBy(() -> zero.geocode("anywhere", "Bangalore", "560001", "order"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");
  }

  @Test
  void ac006_directionsIncludesTrafficDuration() {
    Map<String, Object> data =
        service.directions(
            new LatLng(12.9716, 77.6412), new LatLng(12.9784, 77.6408), "DRIVING", "rider");
    assertThat(data).containsKeys("duration_seconds", "duration_in_traffic_seconds");
    assertThat((Integer) data.get("duration_in_traffic_seconds"))
        .isGreaterThanOrEqualTo((Integer) data.get("duration_seconds"));
  }

  @Test
  void ac007_allCallsLoggedIncludingCacheHits() {
    service.geocode("Indiranagar", "Bangalore", "560038", "order");
    service.geocode("Indiranagar", "Bangalore", "560038", "order");
    service.reverseGeocode(12.9716, 77.6412, "customer");
    service.zoneCheck(
        12.97, 77.64, new double[][] {{12.9, 77.6}, {12.9, 77.7}, {13.0, 77.7}}, "z", "d");
    assertThat(logs.size()).isEqualTo(4);
    assertThat(logs.all().stream().filter(l -> l.wasCacheHit()).count()).isEqualTo(1);
  }

  @Test
  void reverseCacheHitAndRedisPath() {
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(null);

    MapsService withRedis =
        new MapsService(
            client, logs, cache, (t, a, i, p) -> {}, Clock.fixed(NOW, ZoneOffset.UTC), provider);
    Map<String, Object> first = withRedis.reverseGeocode(12.9716, 77.6412, "customer");
    assertThat(first.get("cache_hit")).isEqualTo(false);
    verify(ops).set(anyString(), anyString(), any(Duration.class));

    when(ops.get(anyString()))
        .thenReturn("12.9716\n77.6412\nCached Addr\npid\n" + NOW + "\n" + NOW.plusSeconds(3600));
    Map<String, Object> hit = withRedis.reverseGeocode(12.9716, 77.6412, "customer");
    assertThat(hit.get("cache_hit")).isEqualTo(true);
    assertThat(hit.get("formatted_address")).isEqualTo("Cached Addr");
  }

  @Test
  void redisCorruptAndExpiredIgnored() {
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn("bad");
    MapsService withRedis =
        new MapsService(
            client, logs, cache, (t, a, i, p) -> {}, Clock.fixed(NOW, ZoneOffset.UTC), provider);
    assertThat(withRedis.geocode("Indiranagar", "Blr", "560038", "o").get("cache_hit"))
        .isEqualTo(false);

    when(ops.get(anyString()))
        .thenReturn("12.97\n77.59\na\np\n" + NOW.minusSeconds(100000) + "\n" + NOW.minusSeconds(1));
    assertThat(withRedis.geocode("Indiranagar again", "Blr", "560038", "o").get("cache_hit"))
        .isEqualTo(false);
  }

  @Test
  void distanceMatrixAndDirectionsErrors() {
    MapsService unavailable =
        new MapsService(
            new StubMapsClient(false, true),
            logs,
            cache,
            (t, a, i, p) -> {},
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    assertThatThrownBy(
            () ->
                unavailable.distanceMatrix(
                    List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "DRIVING", "d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(
            () -> unavailable.directions(new LatLng(1, 2), new LatLng(3, 4), "BICYCLING", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(() -> unavailable.geocode("x", "y", "z", "o"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(() -> unavailable.reverseGeocode(1, 2, "c"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");

    assertThatThrownBy(() -> service.directions(null, new LatLng(1, 2), null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.geocode(null, null, null, "o"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    List<LatLng> dests = new ArrayList<>();
    for (int i = 0; i < 26; i++) {
      dests.add(new LatLng(12.97, 77.64 + i * 0.001));
    }
    assertThatThrownBy(
            () -> service.distanceMatrix(List.of(new LatLng(12.97, 77.64)), dests, "WALKING", "d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_DESTINATIONS");

    assertThat(service.distanceMatrix(null, null, "FLYING", "d").get("matrix"))
        .isEqualTo(List.of());
  }

  @Test
  void reverseZeroResultsAndNormalizeHelpers() {
    MapsService zero =
        new MapsService(
            new StubMapsClient(true, false),
            logs,
            cache,
            (t, a, i, p) -> {},
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    assertThatThrownBy(() -> zero.reverseGeocode(1, 2, "c"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");
    assertThat(MapsService.normalizeAddress("  Foo   Bar ")).isEqualTo("foo bar");
    assertThat(MapsService.round4(12.97165)).isEqualTo("12.9717");
  }

  @Test
  void stubZeroViaNowhereAddress() {
    assertThatThrownBy(() -> service.geocode("zzzzz nowhere", "x", "1", "o"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");
  }

  @Test
  void dbCacheHitOnFreshServiceInstance() {
    service.geocode("Indiranagar", "Bangalore", "560038", "order");
    int calls = googleCalls.get();
    MapsService fresh =
        new MapsService(
            client, logs, cache, (t, a, i, p) -> {}, Clock.fixed(NOW, ZoneOffset.UTC), null);
    Map<String, Object> hit = fresh.geocode("Indiranagar", "Bangalore", "560038", "order");
    assertThat(hit.get("cache_hit")).isEqualTo(true);
    assertThat(googleCalls.get()).isEqualTo(calls);
  }

  @Test
  void mgRoadGeocodeAndBicyclingMode() {
    assertThat(service.geocode("MG Road", "Bangalore", "560001", "o").get("place_id"))
        .isEqualTo("ChIJ_stub_mgroad");
    Map<String, Object> matrix =
        service.distanceMatrix(
            List.of(new LatLng(12.97, 77.64)),
            List.of(new LatLng(12.98, 77.65)),
            "BICYCLING",
            "dispatch");
    assertThat(matrix.get("matrix")).isNotNull();
  }
}
