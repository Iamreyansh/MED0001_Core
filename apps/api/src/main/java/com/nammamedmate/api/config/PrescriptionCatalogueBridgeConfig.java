package com.nammamedmate.api.config;

import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.InventoryStockPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Catalogue schedule + inventory stock for prescription domain. */
@Configuration
public class PrescriptionCatalogueBridgeConfig {

  @Bean
  @Primary
  CatalogueSchedulePort jdbcCatalogueSchedulePort(JdbcTemplate jdbc) {
    return medicineName -> {
      if (medicineName == null || medicineName.isBlank()) {
        return Optional.empty();
      }
      List<String> rows =
          jdbc.query(
              """
              SELECT schedule FROM medicine_master
               WHERE UPPER(name) = UPPER(?)
               LIMIT 1
              """,
              (rs, i) -> rs.getString("schedule"),
              medicineName.trim());
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      String schedule = rows.getFirst();
      if (schedule == null || schedule.isBlank() || "OTC".equalsIgnoreCase(schedule)) {
        return Optional.empty();
      }
      return Optional.of(schedule.trim().toUpperCase());
    };
  }

  @Bean
  @Primary
  InventoryStockPort jdbcInventoryStockPort(JdbcTemplate jdbc) {
    return (pharmacyId, medicineName) -> {
      if (pharmacyId == null || medicineName == null || medicineName.isBlank()) {
        return Optional.empty();
      }
      List<InventoryStockPort.StockInfo> rows =
          jdbc.query(
              """
              SELECT COALESCE(pp.total_stock_units, 0) AS qty,
                     COALESCE(pp.mrp_paise, 0) AS mrp
                FROM pharmacy_product pp
               WHERE pp.pharmacy_id = ?
                 AND pp.deleted_at IS NULL
                 AND UPPER(pp.name) = UPPER(?)
               LIMIT 1
              """,
              (rs, i) ->
                  new InventoryStockPort.StockInfo(
                      rs.getInt("qty") > 0, rs.getInt("qty"), rs.getLong("mrp")),
              pharmacyId,
              medicineName.trim());
      return rows.stream().findFirst();
    };
  }
}
