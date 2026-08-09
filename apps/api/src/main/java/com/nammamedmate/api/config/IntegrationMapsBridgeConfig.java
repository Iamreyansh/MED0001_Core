package com.nammamedmate.api.config;

import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.integration.application.MapsService;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort.RouteEstimate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridge: customer {@link GeocodePort} + rider {@link DistanceMatrixPort} →
 * integration {@link MapsService}.
 */
@Configuration
public class IntegrationMapsBridgeConfig {

  @Bean
  @Primary
  GeocodePort integrationGeocodePort(MapsService maps) {
    return (latitude, longitude) -> {
      Map<String, Object> data = maps.reverseGeocode(latitude, longitude, "customer");
      return new GeocodePort.SuggestedAddress(
          "",
          str(data.get("area_locality")),
          str(data.get("city")),
          str(data.get("state")),
          str(data.get("pincode")),
          str(data.get("formatted_address")),
          latitude,
          longitude);
    };
  }

  @Bean
  @Primary
  DistanceMatrixPort integrationDistanceMatrixPort(MapsService maps) {
    DistanceMatrixPort fallback = new StubDistanceMatrixAdapter();
    return new DistanceMatrixPort() {
      @Override
      public double distanceKm(UUID riderId, Double pharmacyLat, Double pharmacyLng) {
        return fallback.distanceKm(riderId, pharmacyLat, pharmacyLng);
      }

      @Override
      public RouteEstimate estimateDriving(
          double originLat, double originLng, double destLat, double destLng) {
        Map<String, Object> data =
            maps.distanceMatrix(
                List.of(
                    new com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng(
                        originLat, originLng)),
                List.of(
                    new com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng(
                        destLat, destLng)),
                "DRIVING",
                "rider");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matrix = (List<Map<String, Object>>) data.get("matrix");
        if (matrix == null || matrix.isEmpty()) {
          return fallback.estimateDriving(originLat, originLng, destLat, destLng);
        }
        Map<String, Object> cell = matrix.get(0);
        int meters = ((Number) cell.get("distance_meters")).intValue();
        int seconds = ((Number) cell.get("duration_seconds")).intValue();
        double km = Math.round((meters / 1000.0) * 10.0) / 10.0;
        int minutes = Math.max(1, (int) Math.round(seconds / 60.0));
        return new RouteEstimate(km, minutes);
      }
    };
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }
}
