package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import com.nammamedmate.observability_ops.domain.RemediationTriggerType;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcRemediationStoresTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void playbookStoreCoversPaths() throws Exception {
    UUID id = UUID.fromString("02000002-0001-4000-8000-000000000001");
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("alert_type")).thenReturn("ZONE_DARK");
    when(rs.getString("auto_remediation_action")).thenReturn("REQUEST_RIDERS");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("threshold")).thenReturn("{\"dark_duration_minutes\":30}");
    when(rs.getBoolean("is_enabled")).thenReturn(true);
    when(rs.getTimestamp("last_triggered_at")).thenReturn(null);
    when(rs.getObject("updated_by")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(ts));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);

    JdbcRemediationPlaybookStore store = new JdbcRemediationPlaybookStore(jdbc, new ObjectMapper());
    assertThat(store.findAll()).hasSize(1);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByAlertType(AlertType.ZONE_DARK)).isPresent();
    assertThat(store.update(id, false, null, UUID.randomUUID(), ts)).isNotNull();
    store.touchLastTriggered(id, ts);
  }

  @Test
  @SuppressWarnings("unchecked")
  void logStoreCoversPaths() throws Exception {
    UUID id = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("alert_id")).thenReturn(null);
    when(rs.getObject("playbook_id")).thenReturn(null);
    when(rs.getString("action_type")).thenReturn("REQUEST_RIDERS");
    when(rs.getString("trigger_type")).thenReturn("MANUAL");
    when(rs.getString("target_entity_type")).thenReturn("ZONE");
    when(rs.getObject("target_entity_id")).thenReturn(target);
    when(rs.getString("action_details")).thenReturn("{\"riders_notified\":1}", " ", null);
    when(rs.getString("status")).thenReturn("SUCCESS");
    when(rs.getObject("triggered_by")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(ts));
    when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(ts)).thenReturn(null);
    when(rs.getString("error_message")).thenReturn(null);
    when(jdbc.update(
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
            any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(1)
        .thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));

    JdbcRemediationLogStore store = new JdbcRemediationLogStore(jdbc, new ObjectMapper());
    RemediationLogEntry entry =
        new RemediationLogEntry(
            id,
            null,
            null,
            RemediationActionType.REQUEST_RIDERS,
            RemediationTriggerType.MANUAL,
            "ZONE",
            target,
            Map.of(),
            RemediationStatus.INITIATED,
            null,
            ts,
            ts,
            null);
    store.insert(entry);
    store.complete(id, RemediationStatus.SUCCESS, null, ts, null);
    RemediationLogEntry openEntry =
        new RemediationLogEntry(
            UUID.randomUUID(),
            null,
            null,
            RemediationActionType.CLEAR_CACHE,
            RemediationTriggerType.MANUAL,
            "CACHE",
            target,
            Map.of(),
            RemediationStatus.INITIATED,
            null,
            ts,
            null,
            null);
    store.insert(openEntry);
    assertThat(store.lastTriggeredAt(RemediationActionType.REQUEST_RIDERS, target)).isPresent();
    assertThat(store.countByActionAndTargetSince(RemediationActionType.REQUEST_RIDERS, target, ts))
        .isEqualTo(1);
    assertThat(store.countByActionAndTargetSince(RemediationActionType.REQUEST_RIDERS, target, ts))
        .isZero();
    assertThat(store.list(null, null, null, null, 1, 20).entries()).hasSize(1);
    assertThat(
            store
                .list(
                    RemediationActionType.REQUEST_RIDERS, RemediationStatus.SUCCESS, ts, ts, 1, 20)
                .entries())
        .hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsonErrorAndNullBranches() throws Exception {
    ObjectMapper boom = mock(ObjectMapper.class);
    when(boom.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    when(boom.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    JdbcRemediationPlaybookStore pb = new JdbcRemediationPlaybookStore(jdbc, boom);
    JdbcRemediationLogStore logStore = new JdbcRemediationLogStore(jdbc, boom);
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    try {
      pb.update(id, true, Map.of("a", 1), null, ts);
    } catch (IllegalStateException ignored) {
    }
    try {
      logStore.insert(
          new RemediationLogEntry(
              id,
              null,
              null,
              RemediationActionType.CLEAR_CACHE,
              RemediationTriggerType.MANUAL,
              "CACHE",
              id,
              Map.of("a", 1),
              RemediationStatus.INITIATED,
              null,
              ts,
              ts,
              null));
    } catch (IllegalStateException ignored) {
    }
    try {
      logStore.complete(id, RemediationStatus.FAILED, Map.of(), ts, "e");
    } catch (IllegalStateException ignored) {
    }

    when(rs.getString("threshold")).thenReturn(null, "", "  ", "{\"a\":1}");
    when(rs.getString("action_details")).thenReturn(null, "", "  ", "{}");
    when(rs.getTimestamp("last_triggered_at")).thenReturn(Timestamp.from(ts));
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("alert_type")).thenReturn("ZONE_DARK");
    when(rs.getString("auto_remediation_action")).thenReturn("REQUEST_RIDERS");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getBoolean("is_enabled")).thenReturn(true);
    when(rs.getObject("updated_by")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(ts));
    when(rs.getObject("alert_id")).thenReturn(null);
    when(rs.getObject("playbook_id")).thenReturn(null);
    when(rs.getString("action_type")).thenReturn("REQUEST_RIDERS");
    when(rs.getString("trigger_type")).thenReturn("AUTO");
    when(rs.getString("target_entity_type")).thenReturn("ZONE");
    when(rs.getObject("target_entity_id")).thenReturn(id);
    when(rs.getString("status")).thenReturn("INITIATED");
    when(rs.getObject("triggered_by")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(ts));
    when(rs.getString("error_message")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    JdbcRemediationPlaybookStore okPb = new JdbcRemediationPlaybookStore(jdbc, new ObjectMapper());
    when(rs.getString("threshold")).thenReturn(null);
    assertThat(okPb.findAll()).hasSize(1);
    when(rs.getString("threshold")).thenReturn("   ");
    assertThat(okPb.findAll()).hasSize(1);
    when(rs.getString("threshold")).thenReturn("{\"a\":1}");
    assertThat(okPb.findAll()).hasSize(1);

    JdbcRemediationLogStore okLog = new JdbcRemediationLogStore(jdbc, new ObjectMapper());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(null)
        .thenReturn(1L);
    assertThat(okLog.list(null, null, null, null, 1, 20).total()).isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(rs.getString("action_details")).thenReturn(null);
    assertThat(okLog.list(null, null, null, null, 1, 20).entries()).hasSize(1);
    when(rs.getString("action_details")).thenReturn("");
    assertThat(okLog.list(null, null, null, null, 1, 20).entries()).hasSize(1);
    when(rs.getString("action_details")).thenReturn("   ");
    assertThat(okLog.list(null, null, null, null, 1, 20).entries()).hasSize(1);
    when(rs.getString("action_details")).thenReturn("{}");
    assertThat(okLog.list(null, null, null, null, 1, 20).entries()).hasSize(1);

    when(rs.getString("threshold")).thenReturn("{bad");
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              try {
                return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
              } catch (IllegalStateException e) {
                throw e;
              }
            });
    assertThatThrownBy(okPb::findAll).isInstanceOf(IllegalStateException.class);

    when(rs.getString("action_details")).thenReturn("{bad");
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThatThrownBy(() -> okLog.list(null, null, null, null, 1, 20))
        .isInstanceOf(IllegalStateException.class);
  }
}
