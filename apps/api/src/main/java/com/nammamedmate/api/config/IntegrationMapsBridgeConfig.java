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
import org.springframework.jdbc.core.JdbcTemplate;

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
  DistanceMatrixPort integrationDistanceMatrixPort(MapsService maps, JdbcTemplate jdbc) {
    DistanceMatrixPort fallback = new StubDistanceMatrixAdapter();
    return new DistanceMatrixPort() {
      @Override
      public double distanceKm(UUID riderId, Double pharmacyLat, Double pharmacyLng) {
        if (pharmacyLat == null || pharmacyLng == null) {
          return fallback.distanceKm(riderId, pharmacyLat, pharmacyLng);
        }
        Double[] origin = lastRiderLatLng(jdbc, riderId);
        if (origin == null) {
          return fallback.distanceKm(riderId, pharmacyLat, pharmacyLng);
        }
        return estimateDriving(origin[0], origin[1], pharmacyLat, pharmacyLng).distanceKm();
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

  private static Double[] lastRiderLatLng(JdbcTemplate jdbc, UUID riderId) {
    if (jdbc == null || riderId == null) {
      return null;
    }
    var rows =
        jdbc.query(
            """
            SELECT lat, lng FROM rider_locations
             WHERE rider_id = ?
             ORDER BY recorded_at DESC
             LIMIT 1
            """,
            (rs, i) -> new Double[] {rs.getDouble("lat"), rs.getDouble("lng")},
            riderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }
}
