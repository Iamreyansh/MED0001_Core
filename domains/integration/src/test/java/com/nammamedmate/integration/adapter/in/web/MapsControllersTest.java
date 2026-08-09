package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.integration.application.MapsService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapsControllersTest {

  private MapsService maps;
  private MapsIntegrationController controller;

  @BeforeEach
  void setUp() {
    maps = mock(MapsService.class);
    controller = new MapsIntegrationController(maps, new InternalServiceAuth("tok"));
  }

  @Test
  void requiresToken() {
    assertThatThrownBy(() -> controller.geocode(null, null, null)).isInstanceOf(AppException.class);
  }

  @Test
  void geocodeAndReverse() {
    when(maps.geocode(any(), any(), any(), any())).thenReturn(Map.of("cache_hit", false));
    when(maps.reverseGeocode(anyDouble(), anyDouble(), any()))
        .thenReturn(Map.of("formatted_address", "x"));
    assertThat(
            controller
                .geocode(
                    "tok", "order", new MapsIntegrationController.GeocodeRequest("a", "b", "c"))
                .data()
                .get("cache_hit"))
        .isEqualTo(false);
    controller.geocode("tok", null, null);
    assertThat(
            controller
                .reverseGeocode(
                    "tok", "c", new MapsIntegrationController.ReverseGeocodeRequest(1, 2))
                .data()
                .get("formatted_address"))
        .isEqualTo("x");
    controller.reverseGeocode("tok", null, null);
  }

  @Test
  void matrixDirectionsZone() {
    when(maps.distanceMatrix(anyList(), anyList(), any(), any()))
        .thenReturn(Map.of("matrix", List.of()));
    when(maps.directions(any(), any(), any(), any()))
        .thenReturn(Map.of("duration_in_traffic_seconds", 1));
    when(maps.zoneCheck(anyDouble(), anyDouble(), any(), any(), any()))
        .thenReturn(Map.of("inside", true));

    ApiResponse<Map<String, Object>> matrix =
        controller.distanceMatrix(
            "tok",
            "dispatch",
            new MapsIntegrationController.DistanceMatrixRequest(
                List.of(new MapsIntegrationController.PointBody(1, 2)),
                List.of(new MapsIntegrationController.PointBody(3, 4)),
                "DRIVING"));
    assertThat(matrix.success()).isTrue();
    controller.distanceMatrix("tok", null, null);
    controller.distanceMatrix(
        "tok", null, new MapsIntegrationController.DistanceMatrixRequest(null, null, null));

    assertThat(
            controller
                .directions(
                    "tok",
                    "rider",
                    new MapsIntegrationController.DirectionsRequest(
                        new MapsIntegrationController.PointBody(1, 2),
                        new MapsIntegrationController.PointBody(3, 4),
                        "DRIVING"))
                .data()
                .get("duration_in_traffic_seconds"))
        .isEqualTo(1);
    controller.directions("tok", null, null);

    assertThat(
            controller
                .zoneCheck(
                    "tok",
                    "dispatch",
                    new MapsIntegrationController.ZoneCheckRequest(
                        new MapsIntegrationController.PointBody(12.97, 77.64),
                        List.of(List.of(12.9, 77.6), List.of(12.9, 77.7), List.of(13.0, 77.65)),
                        "z1"))
                .data()
                .get("inside"))
        .isEqualTo(true);
    controller.zoneCheck("tok", null, null);
    controller.zoneCheck(
        "tok",
        null,
        new MapsIntegrationController.ZoneCheckRequest(null, List.of(List.of(1.0)), null));
  }
}
