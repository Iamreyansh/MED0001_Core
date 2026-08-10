package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.CustomerContactPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCustomerContactAdapter implements CustomerContactPort {

  private final JdbcTemplate jdbc;

  public JdbcCustomerContactAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Contact> find(UUID customerId) {
    List<Contact> rows =
        jdbc.query(
            """
            SELECT name, phone FROM customers
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> new Contact(rs.getString("name"), rs.getString("phone")),
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public int previousOrdersCount(UUID customerId, UUID pharmacyId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM orders
            WHERE customer_id = ? AND pharmacy_id = ?
              AND deleted_at IS NULL AND status <> 'CANCELLED'
            """,
            Integer.class,
            customerId,
            pharmacyId);
    return n == null ? 0 : n;
  }
}
