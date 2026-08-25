package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRecipientIdentityPort implements RecipientIdentityPort {

  private final JdbcTemplate jdbc;

  public JdbcRecipientIdentityPort(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> findCustomerIdByPhone(String phone) {
    if (phone == null || phone.isBlank()) {
      return Optional.empty();
    }
    List<UUID> rows =
        jdbc.query(
            """
            SELECT id FROM customers
            WHERE phone = ? AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) -> (UUID) rs.getObject("id"),
            phone.trim());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<String> findPhoneByCustomerId(UUID customerId) {
    if (customerId == null) {
      return Optional.empty();
    }
    List<String> rows =
        jdbc.query(
            """
            SELECT phone FROM customers
            WHERE id = ? AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) -> rs.getString("phone"),
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<String> findPhoneByRiderId(UUID riderId) {
    if (riderId == null) {
      return Optional.empty();
    }
    List<String> rows =
        jdbc.query(
            """
            SELECT phone FROM riders
            WHERE id = ? AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) -> rs.getString("phone"),
            riderId);
    return rows.stream().findFirst();
  }
}
