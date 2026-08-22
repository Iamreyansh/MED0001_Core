package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.PriceCeilingPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enforces medicine_master.mrp_ceiling_paise at checkout. */
public final class JdbcPriceCeilingAdapter implements PriceCeilingPort {

  private final JdbcTemplate jdbc;

  public JdbcPriceCeilingAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void assertWithinCeiling(UUID pharmacyId, List<Line> lines) {
    if (lines == null) {
      return;
    }
    for (Line line : lines) {
      if (line == null || line.medicineId() == null) {
        continue;
      }
      List<Ceiling> rows =
          jdbc.query(
              "SELECT name, mrp_ceiling_paise FROM medicine_master WHERE id = ?",
              (rs, i) ->
                  new Ceiling(rs.getString("name"), (Long) rs.getObject("mrp_ceiling_paise")),
              line.medicineId());
      if (rows.isEmpty() || rows.getFirst().ceilingPaise() == null) {
        continue;
      }
      long ceiling = rows.getFirst().ceilingPaise();
      if (line.unitPricePaise() > ceiling) {
        String name = rows.getFirst().name() == null ? "medicine" : rows.getFirst().name();
        throw new AppException(
            "PRICE_CEILING_VIOLATED",
            "This pharmacy's price for "
                + name
                + " (Rs "
                + paiseToRupees(line.unitPricePaise())
                + ") exceeds the platform ceiling (Rs "
                + paiseToRupees(ceiling)
                + "). Please choose another pharmacy.",
            400);
      }
    }
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  private record Ceiling(String name, Long ceilingPaise) {}
}
