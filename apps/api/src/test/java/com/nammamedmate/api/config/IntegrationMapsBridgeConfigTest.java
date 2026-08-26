package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.integration.application.MapsService;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort.RouteEstimate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class IntegrationMapsBridgeConfigTest {

  @Test
  void geocodePortMapsReverse() {
    MapsService maps = mock(MapsService.class);
    when(maps.reverseGeocode(eq(12.97), eq(77.64), eq("customer")))
        .thenReturn(
            Map.of(
                "area_locality",
                "Indiranagar",
                "city",
                "Bengaluru",
                "state",
                "Karnataka",
                "pincode",
                "560038",
                "formatted_address",
                "Addr"));
    GeocodePort port = new IntegrationMapsBridgeConfig().integrationGeocodePort(maps);
    GeocodePort.SuggestedAddress addr = port.reverseGeocode(12.97, 77.64);
    assertThat(addr.city()).isEqualTo("Bengaluru");
    assertThat(addr.formattedAddress()).isEqualTo("Addr");
  }

  @Test
  void distanceMatrixPortEstimateDriving() {
    MapsService maps = mock(MapsService.class);
    when(maps.distanceMatrix(anyList(), anyList(), anyString(), anyString()))
        .thenReturn(
            Map.of(
                "matrix",
                List.of(Map.of("distance_meters", 2200, "duration_seconds", 300, "status", "OK"))));
    DistanceMatrixPort port =
        new IntegrationMapsBridgeConfig().integrationDistanceMatrixPort(maps, null);
    RouteEstimate est = port.estimateDriving(12.97, 77.64, 12.98, 77.65);
    assertThat(est.distanceKm()).isEqualTo(2.2);
    assertThat(est.durationMinutes()).isEqualTo(5);
    assertThat(port.distanceKm(UUID.randomUUID(), 12.0, 77.0)).isPositive();

    when(maps.distanceMatrix(anyList(), anyList(), anyString(), anyString()))
        .thenReturn(Map.of("matrix", List.of()));
    assertThat(port.estimateDriving(12.97, 77.64, 12.98, 77.65).durationMinutes()).isPositive();
    assertThat(port.distanceKm(UUID.randomUUID(), null, 77.0)).isPositive();

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID rider = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(java.util.Collections.singletonList(new Double[] {12.97, 77.64}));
    DistanceMatrixPort live =
        new IntegrationMapsBridgeConfig().integrationDistanceMatrixPort(maps, jdbc);
    when(maps.distanceMatrix(anyList(), anyList(), anyString(), anyString()))
        .thenReturn(
            Map.of(
                "matrix",
                List.of(Map.of("distance_meters", 1100, "duration_seconds", 180, "status", "OK"))));
    assertThat(live.distanceKm(rider, 12.98, 77.65)).isEqualTo(1.1);
  }
}
