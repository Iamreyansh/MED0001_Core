package com.nammamedmate.integration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.domain.CommunicationChannelConfig;
import com.nammamedmate.integration.domain.CommunicationConfigAudit;
import com.nammamedmate.integration.domain.CommunicationCostDaily;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CommunicationJdbcStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void channelConfigStoreFindUpdateReset() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCommunicationChannelConfigStore store = new JdbcCommunicationChannelConfigStore(jdbc);
    UUID updater = UUID.randomUUID();
    CommunicationChannelConfig row =
        new CommunicationChannelConfig(
            "SMS",
            true,
            "MSG91",
            "TWILIO",
            "medmate/comms/sms",
            50000,
            3,
            "HEALTHY",
            NOW,
            updater,
            NOW);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<CommunicationChannelConfig> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockConfigRs(row, true), 0));
            });
    assertThat(store.findAll()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq("SMS")))
        .thenAnswer(
            inv -> {
              RowMapper<CommunicationChannelConfig> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockConfigRs(row, false), 0));
            });
    assertThat(store.findByChannel("SMS")).isPresent();
    assertThat(store.findByChannel("SMS").orElseThrow().lastHealthCheckAt()).isNull();

    store.update(
        new CommunicationChannelConfig(
            "SMS", false, "MSG91", null, "medmate/comms/sms", 1, 0, "DOWN", null, null, NOW));
    store.update(row); // covers ts(non-null Instant)
    store.resetAllDailySentCounts();
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
            any());
    verify(jdbc).update("UPDATE communication_channel_configs SET daily_sent_count = 0");
  }

  @Test
  @SuppressWarnings("unchecked")
  void costDailyStoreFindAndUpsert() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCommunicationCostDailyStore store = new JdbcCommunicationCostDailyStore(jdbc);
    UUID id = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 7, 24);
    CommunicationCostDaily row =
        new CommunicationCostDaily(id, date, "SMS", "MSG91", 2, 2, 1, new BigDecimal("0.24"), NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), eq("SMS"), eq("MSG91")))
        .thenAnswer(
            inv -> {
              RowMapper<CommunicationCostDaily> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockCostRs(row, true), 0));
            });
    assertThat(store.find(date, "SMS", "MSG91")).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<CommunicationCostDaily> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockCostRs(row, false), 0));
            });
    assertThat(store.findByDate(date)).hasSize(1);
    assertThat(store.findByDate(date).get(0).createdAt()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("SMS"), any(), any()))
        .thenReturn(List.of());
    assertThat(store.findByChannelAndDateRange("SMS", date, date)).isEmpty();

    store.upsertIncrement(date, "SMS", "MSG91", 1, 1, 0, new BigDecimal("0.12"));
    verify(jdbc)
        .update(anyString(), any(), any(), eq("SMS"), eq("MSG91"), eq(1), eq(1), eq(0), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void auditStoreInsertFindAndJsonEdges() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcCommunicationConfigAuditStore store = new JdbcCommunicationConfigAuditStore(jdbc, mapper);
    CommunicationConfigAudit audit =
        new CommunicationConfigAudit(
            UUID.randomUUID(),
            "SMS",
            UUID.randomUUID(),
            Map.of("is_enabled", Map.of("from", true, "to", false)),
            "PASSED",
            NOW);
    store.insert(audit);
    store.insert(
        new CommunicationConfigAudit(
            UUID.randomUUID(), "SMS", UUID.randomUUID(), null, "SKIPPED", NOW));

    when(jdbc.query(anyString(), any(RowMapper.class), eq("SMS")))
        .thenAnswer(
            inv -> {
              RowMapper<CommunicationConfigAudit> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(audit.id());
              when(rs.getString("channel")).thenReturn("SMS");
              when(rs.getObject("changed_by")).thenReturn(audit.changedBy());
              when(rs.getString("changed_fields")).thenReturn("{\"a\":1}");
              when(rs.getString("connectivity_test_result")).thenReturn("PASSED");
              when(rs.getTimestamp("changed_at")).thenReturn(Timestamp.from(NOW));
              ResultSet blank = mock(ResultSet.class);
              when(blank.getObject("id")).thenReturn(UUID.randomUUID());
              when(blank.getString("channel")).thenReturn("SMS");
              when(blank.getObject("changed_by")).thenReturn(UUID.randomUUID());
              when(blank.getString("changed_fields")).thenReturn(" ");
              when(blank.getString("connectivity_test_result")).thenReturn("SKIPPED");
              when(blank.getTimestamp("changed_at")).thenReturn(Timestamp.from(NOW));
              ResultSet bad = mock(ResultSet.class);
              when(bad.getObject("id")).thenReturn(UUID.randomUUID());
              when(bad.getString("channel")).thenReturn("SMS");
              when(bad.getObject("changed_by")).thenReturn(UUID.randomUUID());
              when(bad.getString("changed_fields")).thenReturn("not-json");
              when(bad.getString("connectivity_test_result")).thenReturn("FAILED");
              when(bad.getTimestamp("changed_at")).thenReturn(Timestamp.from(NOW));
              ResultSet nil = mock(ResultSet.class);
              when(nil.getObject("id")).thenReturn(UUID.randomUUID());
              when(nil.getString("channel")).thenReturn("SMS");
              when(nil.getObject("changed_by")).thenReturn(UUID.randomUUID());
              when(nil.getString("changed_fields")).thenReturn(null);
              when(nil.getString("connectivity_test_result")).thenReturn("SKIPPED");
              when(nil.getTimestamp("changed_at")).thenReturn(Timestamp.from(NOW));
              return List.of(
                  rowMapper.mapRow(rs, 0),
                  rowMapper.mapRow(blank, 1),
                  rowMapper.mapRow(bad, 2),
                  rowMapper.mapRow(nil, 3));
            });
    assertThat(store.findByChannel("SMS")).hasSize(4);

    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    JdbcCommunicationConfigAuditStore failing =
        new JdbcCommunicationConfigAuditStore(jdbc, badMapper);
    assertThatThrownBy(() -> failing.insert(audit)).isInstanceOf(IllegalStateException.class);
  }

  private static ResultSet mockConfigRs(CommunicationChannelConfig row, boolean withHealth)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("channel")).thenReturn(row.channel());
    when(rs.getBoolean("is_enabled")).thenReturn(row.enabled());
    when(rs.getString("provider")).thenReturn(row.provider());
    when(rs.getString("fallback_provider")).thenReturn(row.fallbackProvider());
    when(rs.getString("secrets_manager_key")).thenReturn(row.secretsManagerKey());
    when(rs.getInt("daily_send_limit")).thenReturn(row.dailySendLimit());
    when(rs.getInt("daily_sent_count")).thenReturn(row.dailySentCount());
    when(rs.getString("current_status")).thenReturn(row.currentStatus());
    when(rs.getTimestamp("last_health_check_at"))
        .thenReturn(withHealth ? Timestamp.from(row.lastHealthCheckAt()) : null);
    when(rs.getObject("updated_by")).thenReturn(row.updatedBy());
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(row.updatedAt()));
    return rs;
  }

  private static ResultSet mockCostRs(CommunicationCostDaily row, boolean withCreated)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(row.id());
    when(rs.getDate("date")).thenReturn(java.sql.Date.valueOf(row.date()));
    when(rs.getString("channel")).thenReturn(row.channel());
    when(rs.getString("provider")).thenReturn(row.provider());
    when(rs.getInt("sent_count")).thenReturn(row.sentCount());
    when(rs.getInt("delivered_count")).thenReturn(row.deliveredCount());
    when(rs.getInt("fallback_sent_count")).thenReturn(row.fallbackSentCount());
    when(rs.getBigDecimal("cost_rs")).thenReturn(row.costRs());
    when(rs.getTimestamp("created_at"))
        .thenReturn(withCreated ? Timestamp.from(row.createdAt()) : null);
    return rs;
  }
}
