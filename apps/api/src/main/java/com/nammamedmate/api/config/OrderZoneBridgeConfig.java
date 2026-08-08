package com.nammamedmate.api.config;

import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: order {@link ZoneMembershipPort} → PostGIS {@code zones.polygon} +
 * serviceability (EPIC-011/STORY-005).
 *
 * <p>Pharmacies without {@code zone_id} / polygon stay allowed (legacy fixtures) until zone
 * geometry is assigned; assigned polygons use {@code ST_Covers}.
 */
@Configuration
public class OrderZoneBridgeConfig {

  @Bean
  @Primary
  ZoneMembershipPort jdbcZoneMembershipPort(JdbcTemplate jdbc) {
    return new ZoneMembershipPort() {
      @Override
      public boolean isInPharmacyZone(UUID pharmacyId, double lat, double lng) {
        if (pharmacyId == null) {
          return false;
        }
        Boolean ok =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1
                  FROM pharmacies p
                  LEFT JOIN zones z ON z.id = p.zone_id AND z.deleted_at IS NULL
                  WHERE p.id = ?
                    AND p.deleted_at IS NULL
                    AND (
                      p.zone_id IS NULL
                      OR z.polygon IS NULL
                      OR (
                        z.is_serviceable = TRUE
                        AND ST_Covers(
                          z.polygon,
                          ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                        )
                      )
                    )
                )
                """,
                Boolean.class,
                pharmacyId,
                lng,
                lat);
        return Boolean.TRUE.equals(ok);
      }

      @Override
      public OptionalLong minOrderValuePaise(UUID pharmacyId, double lat, double lng) {
        if (pharmacyId == null) {
          return OptionalLong.empty();
        }
        var rows =
            jdbc.query(
                """
                SELECT z.min_order_value
                FROM pharmacies p
                JOIN zones z ON z.id = p.zone_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                  AND z.deleted_at IS NULL
                  AND z.is_serviceable = TRUE
                  AND z.polygon IS NOT NULL
                  AND ST_Covers(
                    z.polygon,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                  )
                LIMIT 1
                """,
                (rs, i) -> rs.getBigDecimal("min_order_value"),
                pharmacyId,
                lng,
                lat);
        if (rows.isEmpty() || rows.get(0) == null) {
          return OptionalLong.empty();
        }
        BigDecimal rupees = rows.get(0);
        long paise =
            rupees
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return OptionalLong.of(paise);
      }
    };
  }
}
