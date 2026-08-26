package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CashfreeBeneficiaryStore;
import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcCashfreeBeneficiaryStore implements CashfreeBeneficiaryStore {

  private final JdbcTemplate jdbc;

  public JdbcCashfreeBeneficiaryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<CashfreeBeneficiary> MAPPER =
      (rs, i) ->
          new CashfreeBeneficiary(
              (UUID) rs.getObject("id"),
              rs.getString("entity_type"),
              (UUID) rs.getObject("entity_id"),
              rs.getString("cashfree_contact_id"),
              rs.getString("beneficiary_id"),
              rs.getString("bank_name"),
              rs.getString("account_last4"),
              rs.getString("ifsc"),
              rs.getString("account_holder_name"),
              rs.getBoolean("is_active"),
              instant(rs.getTimestamp("created_at")));

  @Override
  public void insert(CashfreeBeneficiary account) {
    jdbc.update(
        """
        INSERT INTO cashfree_beneficiaries (
          id, entity_type, entity_id, cashfree_contact_id, beneficiary_id,
          bank_name, account_last4, ifsc, account_holder_name, is_active, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        account.id(),
        account.entityType(),
        account.entityId(),
        account.cashfreeContactId(),
        account.beneficiaryId(),
        account.bankName(),
        account.accountLast4(),
        account.ifsc(),
        account.accountHolderName(),
        account.active(),
        Timestamp.from(account.createdAt()));
  }

  @Override
  public void deactivate(UUID id) {
    jdbc.update("UPDATE cashfree_beneficiaries SET is_active = FALSE WHERE id = ?", id);
  }

  @Override
  public Optional<CashfreeBeneficiary> findActiveByEntity(String entityType, UUID entityId) {
    List<CashfreeBeneficiary> rows =
        jdbc.query(
            """
            SELECT * FROM cashfree_beneficiaries
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
  public Optional<CashfreeBeneficiary> findByBeneficiaryId(String beneficiaryId) {
    List<CashfreeBeneficiary> rows =
        jdbc.query(
            "SELECT * FROM cashfree_beneficiaries WHERE beneficiary_id = ?", MAPPER, beneficiaryId);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
