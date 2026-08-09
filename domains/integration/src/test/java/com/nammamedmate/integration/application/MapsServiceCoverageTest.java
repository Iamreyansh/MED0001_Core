package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.adapter.in.web.MapsIntegrationController;
import com.nammamedmate.integration.adapter.out.client.StubMapsClient;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort.GeocodeResult;
import com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MapsServiceCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void remainingBranches() throws Exception {
    AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-07-24T10:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return instant.get();
          }
        };
    InMemoryStores.MapsLogs logs = new InMemoryStores.MapsLogs();
    InMemoryStores.GeocodeCache cache = new InMemoryStores.GeocodeCache();
    StubMapsClient stub = new StubMapsClient();
    MapsClientPort client =
        new MapsClientPort() {
          @Override
          public GeocodeResult geocode(String q) {
            if (q != null && q.contains("nullfields")) {
              return new GeocodeResult(1, 2, null, null, "ROOFTOP", "OK");
            }
            return stub.geocode(q);
          }

          @Override
          public ReverseGeocodeResult reverseGeocode(double lat, double lng) {
            return stub.reverseGeocode(lat, lng);
          }

          @Override
          public List<MatrixCell> distanceMatrix(
              List<LatLng> origins, List<LatLng> destinations, String mode) {
            return stub.distanceMatrix(origins, destinations, mode);
          }

          @Override
          public DirectionsResult directions(LatLng origin, LatLng destination, String mode) {
            return stub.directions(origin, destination, mode);
          }
        };
    MapsService service = new MapsService(client, logs, cache, (t, a, i, p) -> {}, clock, null);

    assertThatThrownBy(() -> service.directions(new LatLng(1, 2), null, "DRIVING", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    service.zoneCheck(1, 2, new double[][] {{0, 0}, {0, 1}, {1, 1}}, null, "   ");
    assertThat(logs.all().get(logs.size() - 1).callingService()).isEqualTo("unknown");
    assertThat(logs.all().get(logs.size() - 1).requestSummary()).contains("zone=?");
    service.zoneCheck(1, 2, new double[][] {{0, 0}, {0, 1}, {1, 1}}, "z", null);
    assertThat(logs.all().get(logs.size() - 1).callingService()).isEqualTo("unknown");

    assertThat(service.geocode("Only address", null, null, "svc").get("cache_hit"))
        .isEqualTo(false);
    assertThat(service.geocode("", "OnlyCity", null, "svc").get("lat")).isNotNull();
    assertThat(service.geocode(null, "OnlyCity", null, "svc").get("lat")).isNotNull();
    assertThat(service.geocode(null, null, "560001", "svc").get("lng")).isNotNull();
    assertThat(service.geocode("a", "", "  ", "svc").get("cache_hit")).isEqualTo(false);

    service.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "", "d");
    service.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "WALKING", "d");
    service.directions(new LatLng(1, 2), new LatLng(3, 4), null, "r");
    service.zoneCheck(1, 2, new double[][] {{0, 0}, {0, 1}, {1, 0}}, "z".repeat(250), "s");

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString()))
        .thenReturn("1.0\n2.0\naddr\npid\nnot-an-instant\n2026-07-24T11:00:00Z");

    MapsService withRedis =
        new MapsService(client, logs, cache, (t, a, i, p) -> {}, clock, provider);
    withRedis.geocode("nullfields street", "c", "1", "o");

    Field memField = MapsService.class.getDeclaredField("memoryCache");
    memField.setAccessible(true);
    ConcurrentHashMap<String, GeocodeCacheEntry> mem =
        (ConcurrentHashMap<String, GeocodeCacheEntry>) memField.get(withRedis);
    String key = MapsService.normalizeAddress("expired mem, c, 1");
    mem.put(
        key,
        new GeocodeCacheEntry(
            key, 1, 2, "x", "p", instant.get().minusSeconds(10), instant.get().minusSeconds(1)));
    when(ops.get(anyString())).thenReturn(null);
    withRedis.geocode("expired mem", "c", "1", "o");

    // budget day rollover
    instant.set(Instant.parse("2026-07-25T01:00:00Z"));
    withRedis.zoneCheck(1, 2, new double[][] {{0, 0}, {0, 1}, {1, 0}}, "z", "s");
  }

  @Test
  void controllerNullElements() {
    MapsService maps = mock(MapsService.class);
    when(maps.distanceMatrix(any(), any(), any(), any())).thenReturn(Map.of("matrix", List.of()));
    when(maps.zoneCheck(anyDouble(), anyDouble(), any(), any(), any()))
        .thenReturn(Map.of("inside", false));
    MapsIntegrationController controller =
        new MapsIntegrationController(maps, new InternalServiceAuth("tok"));
    List<MapsIntegrationController.PointBody> pts = new ArrayList<>();
    pts.add(null);
    pts.add(new MapsIntegrationController.PointBody(1, 2));
    controller.distanceMatrix(
        "tok", "d", new MapsIntegrationController.DistanceMatrixRequest(pts, pts, "DRIVING"));

    List<List<Double>> poly = new ArrayList<>();
    poly.add(null);
    poly.add(List.of(1.0));
    poly.add(List.of(1.0, 2.0));
    controller.zoneCheck(
        "tok", "d", new MapsIntegrationController.ZoneCheckRequest(null, poly, "z"));
    controller.zoneCheck(
        "tok", "d", new MapsIntegrationController.ZoneCheckRequest(null, null, "z"));
  }
}
