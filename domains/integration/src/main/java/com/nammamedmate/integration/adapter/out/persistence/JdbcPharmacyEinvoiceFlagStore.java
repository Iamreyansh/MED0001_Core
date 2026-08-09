package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.PharmacyEinvoiceFlagStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyEinvoiceFlagStore implements PharmacyEinvoiceFlagStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyEinvoiceFlagStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Boolean> findEInvoicingEnabled(UUID pharmacyId) {
    if (pharmacyId == null) {
      return Optional.empty();
    }
    List<Boolean> rows =
        jdbc.query(
            """
            SELECT e_invoicing_enabled FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getBoolean("e_invoicing_enabled"),
            pharmacyId);
    return rows.stream().findFirst();
  }
}
