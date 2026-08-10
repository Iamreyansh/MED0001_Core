package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.CustomerNamePort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCustomerNameAdapter implements CustomerNamePort {

  private final JdbcTemplate jdbc;

  public JdbcCustomerNameAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> findName(UUID customerId) {
    List<String> names =
        jdbc.query(
            "SELECT name FROM customers WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> rs.getString(1),
            customerId);
    return names.stream().filter(n -> n != null && !n.isBlank()).findFirst();
  }
}
