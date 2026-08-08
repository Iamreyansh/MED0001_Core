package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.WalletBalancePort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcWalletBalanceAdapter implements WalletBalancePort {

  private final JdbcTemplate jdbc;

  public JdbcWalletBalanceAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long balancePaise(UUID customerId) {
    List<Long> rows =
        jdbc.query(
            "SELECT wallet_balance_paise FROM customers WHERE id = ?",
            (rs, i) -> rs.getLong(1),
            customerId);
    return rows.isEmpty() ? 0L : rows.getFirst();
  }
}
