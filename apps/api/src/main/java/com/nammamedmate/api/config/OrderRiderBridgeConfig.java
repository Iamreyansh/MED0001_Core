package com.nammamedmate.api.config;

import com.nammamedmate.order.adapter.out.persistence.StubRiderLookupAdapter;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: order RiderLookupPort → real riders table (EPIC-011). Falls back to stub
 * synthesis when the id is not registered yet (order lifecycle ITs / pre-assignment).
 */
@Configuration
public class OrderRiderBridgeConfig {

  @Bean
  @Primary
  RiderLookupPort jdbcRiderLookupPort(JdbcTemplate jdbc) {
    RiderLookupPort stub = new StubRiderLookupAdapter();
    return riderId -> {
      if (riderId == null) {
        return Optional.empty();
      }
      var rows =
          jdbc.query(
              """
              SELECT id, name, phone, vehicle_plate_number
              FROM riders
              WHERE id = ? AND deleted_at IS NULL
              """,
              (rs, i) ->
                  new RiderLookupPort.RiderInfo(
                      (UUID) rs.getObject("id"),
                      rs.getString("name"),
                      rs.getString("phone"),
                      rs.getString("vehicle_plate_number"),
                      null),
              riderId);
      Optional<RiderLookupPort.RiderInfo> found = rows.stream().findFirst();
      return found.isPresent() ? found : stub.findById(riderId);
    };
  }
}
