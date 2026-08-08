package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.domain.DeliveryFeeFormula;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlatformPricingConfigStore implements PlatformPricingConfigStore {

  private final JdbcTemplate jdbc;

  public JdbcPlatformPricingConfigStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> get(String key) {
    List<String> rows =
        jdbc.query(
            "SELECT value FROM platform_pricing_config WHERE key = ?",
            (rs, i) -> rs.getString("value"),
            key);
    return rows.stream().findFirst();
  }

  @Override
  public BigDecimal handlingFeeRupees() {
    return get("handling_fee")
        .map(
            v -> {
              try {
                return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
              } catch (NumberFormatException e) {
                return DeliveryFeeFormula.DEFAULT_HANDLING_FEE;
              }
            })
        .orElse(DeliveryFeeFormula.DEFAULT_HANDLING_FEE);
  }

  @Override
  public void upsert(String key, String value, String description, UUID updatedBy, Instant now) {
    jdbc.update(
        """
        INSERT INTO platform_pricing_config (key, value, description, updated_by, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (key) DO UPDATE SET
          value = EXCLUDED.value,
          description = COALESCE(EXCLUDED.description, platform_pricing_config.description),
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        key,
        value,
        description,
        updatedBy,
        Timestamp.from(now));
  }
}
