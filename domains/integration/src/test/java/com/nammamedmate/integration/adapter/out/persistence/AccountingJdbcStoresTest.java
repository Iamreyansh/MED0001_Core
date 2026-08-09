package com.nammamedmate.integration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.domain.AccountingApiKeyStatuses;
import com.nammamedmate.integration.domain.AccountingIntegration;
import com.nammamedmate.integration.domain.AccountingSyncFrequencies;
import com.nammamedmate.integration.domain.AccountingSyncJob;
import com.nammamedmate.integration.domain.AccountingSyncStatuses;
import com.nammamedmate.integration.domain.AccountingSyncTypes;
import com.nammamedmate.integration.domain.AccountingSystems;
import com.nammamedmate.integration.domain.AccountingTriggeredBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountingJdbcStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @TempDir Path temp;

  @Test
  @SuppressWarnings("unchecked")
  void integrationStoreUpsertAndDue() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAccountingIntegrationStore store = new JdbcAccountingIntegrationStore(jdbc);
    UUID id = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    AccountingIntegration row =
        new AccountingIntegration(
            id,
            pharmacy,
            AccountingSystems.ZOHO_BOOKS,
            "org",
            "Name",
            "enc-a",
            "enc-r",
            NOW.plusSeconds(3600),
            AccountingApiKeyStatuses.CONNECTED,
            true,
            AccountingSyncFrequencies.DAILY,
            NOW,
            NOW,
            AccountingSyncStatuses.COMPLETED,
            NOW,
            NOW);
    store.upsert(row);
    AccountingIntegration sparse =
        new AccountingIntegration(
            id,
            pharmacy,
            AccountingSystems.TALLY,
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.DISCONNECTED,
            false,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    store.upsert(sparse);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingIntegration> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row), 0));
            });
    assertThat(store.findByPharmacyId(pharmacy)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingIntegration> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row), 0));
            });
    assertThat(store.findDueAutoSync(NOW, 5)).hasSize(1);
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void syncJobStoreCrudAndActive() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcAccountingSyncJobStore store = new JdbcAccountingSyncJobStore(jdbc, mapper);
    UUID id = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    AccountingSyncJob job =
        new AccountingSyncJob(
            id,
            pharmacy,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            AccountingSyncStatuses.QUEUED,
            1,
            1,
            0,
            List.of(Map.of("record_id", "x")),
            AccountingTriggeredBy.MANUAL,
            NOW,
            null,
            null);
    store.insert(job);
    store.insert(
        new AccountingSyncJob(
            UUID.randomUUID(),
            pharmacy,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            AccountingSyncStatuses.QUEUED,
            0,
            0,
            0,
            null,
            AccountingTriggeredBy.MANUAL,
            NOW,
            null,
            null));
    store.update(job);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingSyncJob> rowMapper = inv.getArgument(1);
              ResultSet rs = mockJobRs(job, "[{\"record_id\":\"x\"}]");
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingSyncJob> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockJobRs(job, ""), 0));
            });
    assertThat(store.findById(id).orElseThrow().errors()).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy), any(), any()))
        .thenReturn(1);
    assertThat(store.hasActiveJob(pharmacy)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy), any(), any()))
        .thenReturn(0);
    assertThat(store.hasActiveJob(pharmacy)).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(AccountingSyncStatuses.QUEUED), eq(5)))
        .thenReturn(List.of());
    assertThat(store.findQueued(5)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingSyncJob> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockJobRs(job, null), 0));
            });
    assertThat(store.findById(id).orElseThrow().errors()).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<AccountingSyncJob> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockJobRs(job, "not-json"), 0));
            });
    assertThatThrownBy(() -> store.findById(id)).isInstanceOf(IllegalStateException.class);

    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serde"));
    JdbcAccountingSyncJobStore badStore = new JdbcAccountingSyncJobStore(jdbc, badMapper);
    assertThatThrownBy(() -> badStore.insert(job)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void localExportStorePutAndUrl() throws Exception {
    LocalAccountingExportObjectStore store =
        new LocalAccountingExportObjectStore(temp, "file://" + temp);
    store.put("exports/tally.xml", "<ENVELOPE/>".getBytes(), "application/xml");
    assertThat(Files.exists(temp.resolve("exports-tally.xml"))).isTrue();
    assertThat(store.createDownloadUrl("tally.xml", Duration.ofHours(1)))
        .contains("exports-tally.xml")
        .contains("ttl=3600");
    Path blocker = temp.resolve("blocked-file");
    Files.writeString(blocker, "x");
    LocalAccountingExportObjectStore bad =
        new LocalAccountingExportObjectStore(blocker, "file://x");
    assertThatThrownBy(() -> bad.put("k", new byte[] {1}, "text/plain"))
        .isInstanceOf(RuntimeException.class);
  }

  private static ResultSet mockJobRs(AccountingSyncJob job, String errorsJson) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(job.id());
    when(rs.getObject("pharmacy_id")).thenReturn(job.pharmacyId());
    when(rs.getString("accounting_system")).thenReturn(job.accountingSystem());
    when(rs.getString("sync_type")).thenReturn(job.syncType());
    when(rs.getDate("period_from")).thenReturn(java.sql.Date.valueOf(job.periodFrom()));
    when(rs.getDate("period_to")).thenReturn(java.sql.Date.valueOf(job.periodTo()));
    when(rs.getString("status")).thenReturn(job.status());
    when(rs.getInt("records_processed")).thenReturn(job.recordsProcessed());
    when(rs.getInt("records_synced")).thenReturn(job.recordsSynced());
    when(rs.getInt("records_failed")).thenReturn(job.recordsFailed());
    when(rs.getString("errors")).thenReturn(errorsJson);
    when(rs.getString("triggered_by")).thenReturn(job.triggeredBy());
    when(rs.getTimestamp("queued_at")).thenReturn(Timestamp.from(job.queuedAt()));
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    return rs;
  }

  private static ResultSet mockRs(AccountingIntegration row) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(row.id());
    when(rs.getObject("pharmacy_id")).thenReturn(row.pharmacyId());
    when(rs.getString("accounting_system")).thenReturn(row.accountingSystem());
    when(rs.getString("zoho_organization_id")).thenReturn(row.zohoOrganizationId());
    when(rs.getString("zoho_organization_name")).thenReturn(row.zohoOrganizationName());
    when(rs.getString("zoho_access_token")).thenReturn(row.zohoAccessToken());
    when(rs.getString("zoho_refresh_token")).thenReturn(row.zohoRefreshToken());
    when(rs.getTimestamp("zoho_token_expires_at"))
        .thenReturn(Timestamp.from(row.zohoTokenExpiresAt()));
    when(rs.getString("api_key_status")).thenReturn(row.apiKeyStatus());
    when(rs.getBoolean("auto_sync_enabled")).thenReturn(row.autoSyncEnabled());
    when(rs.getString("sync_frequency")).thenReturn(row.syncFrequency());
    when(rs.getTimestamp("next_sync_at")).thenReturn(Timestamp.from(row.nextSyncAt()));
    when(rs.getTimestamp("last_sync_at")).thenReturn(Timestamp.from(row.lastSyncAt()));
    when(rs.getString("last_sync_status")).thenReturn(row.lastSyncStatus());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(row.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(row.updatedAt()));
    return rs;
  }
}
