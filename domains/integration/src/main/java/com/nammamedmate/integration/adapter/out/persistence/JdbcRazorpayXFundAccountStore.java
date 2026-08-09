package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.RazorpayXFundAccountStore;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcRazorpayXFundAccountStore implements RazorpayXFundAccountStore {

  private final JdbcTemplate jdbc;

  public JdbcRazorpayXFundAccountStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<RazorpayXFundAccount> MAPPER =
      (rs, i) ->
          new RazorpayXFundAccount(
              (UUID) rs.getObject("id"),
              rs.getString("entity_type"),
              (UUID) rs.getObject("entity_id"),
              rs.getString("razorpayx_contact_id"),
              rs.getString("fund_account_id"),
              rs.getString("bank_name"),
              rs.getString("account_last4"),
              rs.getString("ifsc"),
              rs.getString("account_holder_name"),
              rs.getBoolean("is_active"),
              instant(rs.getTimestamp("created_at")));

  @Override
  public void insert(RazorpayXFundAccount account) {
    jdbc.update(
        """
        INSERT INTO razorpayx_fund_accounts (
          id, entity_type, entity_id, razorpayx_contact_id, fund_account_id,
          bank_name, account_last4, ifsc, account_holder_name, is_active, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        account.id(),
        account.entityType(),
        account.entityId(),
        account.razorpayxContactId(),
        account.fundAccountId(),
        account.bankName(),
        account.accountLast4(),
        account.ifsc(),
        account.accountHolderName(),
        account.active(),
        Timestamp.from(account.createdAt()));
  }

  @Override
  public void deactivate(UUID id) {
    jdbc.update("UPDATE razorpayx_fund_accounts SET is_active = FALSE WHERE id = ?", id);
  }

  @Override
  public Optional<RazorpayXFundAccount> findActiveByEntity(String entityType, UUID entityId) {
    List<RazorpayXFundAccount> rows =
        jdbc.query(
            """
            SELECT * FROM razorpayx_fund_accounts
            WHERE entity_type = ? AND entity_id = ? AND is_active = TRUE
            ORDER BY created_at DESC
            LIMIT 1
            """,
            MAPPER,
            entityType,
            entityId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<RazorpayXFundAccount> findByFundAccountId(String fundAccountId) {
    List<RazorpayXFundAccount> rows =
        jdbc.query(
            "SELECT * FROM razorpayx_fund_accounts WHERE fund_account_id = ?",
            MAPPER,
            fundAccountId);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
