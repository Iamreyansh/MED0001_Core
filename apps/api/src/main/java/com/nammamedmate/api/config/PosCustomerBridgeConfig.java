package com.nammamedmate.api.config;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition-root: POS customer resolve/create via customers table. */
@Configuration
public class PosCustomerBridgeConfig {

  @Bean
  @Primary
  PosCustomerPort posCustomerPort(JdbcTemplate jdbc) {
    return (phone, name) -> {
      List<CustomerRow> existing =
          jdbc.query(
              """
              SELECT id, name, phone FROM customers
              WHERE phone = ? AND deleted_at IS NULL
              LIMIT 1
              """,
              (rs, i) ->
                  new CustomerRow(
                      (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("phone")),
              phone);
      if (!existing.isEmpty()) {
        CustomerRow row = existing.getFirst();
        String display = row.name() != null ? row.name() : (name != null ? name : phone);
        if ((row.name() == null || row.name().isBlank()) && name != null && !name.isBlank()) {
          jdbc.update(
              "UPDATE customers SET name = ?, updated_at = ? WHERE id = ?",
              name,
              Timestamp.from(Instant.now()),
              row.id());
          display = name;
        }
        return new PosCustomerPort.CustomerRef(row.id(), display, row.phone(), false);
      }
      UUID id = Ids.newId();
      Instant now = Instant.now();
      String displayName = name != null && !name.isBlank() ? name.trim() : null;
      jdbc.update(
          """
          INSERT INTO customers (id, phone, name, preferred_language, segment,
            wallet_balance_paise, loyalty_points, created_at, updated_at)
          VALUES (?, ?, ?, 'en', 'NEW', 0, 0, ?, ?)
          """,
          id,
          phone,
          displayName,
          Timestamp.from(now),
          Timestamp.from(now));
      return new PosCustomerPort.CustomerRef(
          id, displayName != null ? displayName : phone, phone, true);
    };
  }

  private record CustomerRow(UUID id, String name, String phone) {}
}
