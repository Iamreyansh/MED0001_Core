package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.AccountingIntegrationStore;
import com.nammamedmate.integration.domain.AccountingIntegration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAccountingIntegrationStore implements AccountingIntegrationStore {

  private final JdbcTemplate jdbc;

  public JdbcAccountingIntegrationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountingIntegration> findByPharmacyId(UUID pharmacyId) {
    List<AccountingIntegration> rows =
        jdbc.query(
            "SELECT * FROM accounting_integrations WHERE pharmacy_id = ?",
            this::mapRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void upsert(AccountingIntegration integration) {
    jdbc.update(
        """
        INSERT INTO accounting_integrations (
          id, pharmacy_id, accounting_system, zoho_organization_id, zoho_organization_name,
          zoho_access_token, zoho_refresh_token, zoho_token_expires_at, api_key_status,
          auto_sync_enabled, sync_frequency, next_sync_at, last_sync_at, last_sync_status,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (pharmacy_id) DO UPDATE SET
          accounting_system = EXCLUDED.accounting_system,
          zoho_organization_id = EXCLUDED.zoho_organization_id,
          zoho_organization_name = EXCLUDED.zoho_organization_name,
          zoho_access_token = EXCLUDED.zoho_access_token,
          zoho_refresh_token = EXCLUDED.zoho_refresh_token,
          zoho_token_expires_at = EXCLUDED.zoho_token_expires_at,
          api_key_status = EXCLUDED.api_key_status,
          auto_sync_enabled = EXCLUDED.auto_sync_enabled,
          sync_frequency = EXCLUDED.sync_frequency,
          next_sync_at = EXCLUDED.next_sync_at,
          last_sync_at = EXCLUDED.last_sync_at,
          last_sync_status = EXCLUDED.last_sync_status,
          updated_at = EXCLUDED.updated_at
        """,
        integration.id(),
        integration.pharmacyId(),
        integration.accountingSystem(),
        integration.zohoOrganizationId(),
        integration.zohoOrganizationName(),
        integration.zohoAccessToken(),
        integration.zohoRefreshToken(),
        ts(integration.zohoTokenExpiresAt()),
        integration.apiKeyStatus(),
        integration.autoSyncEnabled(),
        integration.syncFrequency(),
        ts(integration.nextSyncAt()),
        ts(integration.lastSyncAt()),
        integration.lastSyncStatus(),
        Timestamp.from(integration.createdAt()),
        Timestamp.from(integration.updatedAt()));
  }

  @Override
  public List<AccountingIntegration> findDueAutoSync(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM accounting_integrations
         WHERE auto_sync_enabled = TRUE
           AND next_sync_at IS NOT NULL
           AND next_sync_at <= ?
           AND api_key_status = 'CONNECTED'
         ORDER BY next_sync_at
         LIMIT ?
        """,
        this::mapRow,
        Timestamp.from(now),
        limit);
  }

  private AccountingIntegration mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AccountingIntegration(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("accounting_system"),
        rs.getString("zoho_organization_id"),
        rs.getString("zoho_organization_name"),
        rs.getString("zoho_access_token"),
        rs.getString("zoho_refresh_token"),
        instant(rs.getTimestamp("zoho_token_expires_at")),
        rs.getString("api_key_status"),
        rs.getBoolean("auto_sync_enabled"),
        rs.getString("sync_frequency"),
        instant(rs.getTimestamp("next_sync_at")),
        instant(rs.getTimestamp("last_sync_at")),
        rs.getString("last_sync_status"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
