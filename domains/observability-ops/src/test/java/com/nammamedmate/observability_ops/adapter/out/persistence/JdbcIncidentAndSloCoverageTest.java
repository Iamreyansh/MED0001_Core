package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.observability_ops.domain.AffectedService;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import com.nammamedmate.observability_ops.domain.IncidentStatusEntry;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcIncidentAndSloCoverageTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void jdbcIncidentStoreCoversAllPaths() throws Exception {
    ObjectMapper om = new ObjectMapper();
    UUID id = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID creator = UUID.randomUUID();
    UUID alertId = UUID.randomUUID();
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    Array services = mock(Array.class);
    when(services.getArray()).thenReturn(new Object[] {"PAYMENT_GATEWAY", null, "DISPATCH"});
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("incident_number")).thenReturn("INC-20260724-001");
    when(rs.getString("title")).thenReturn("t");
    when(rs.getString("severity")).thenReturn("P1");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("status")).thenReturn("DETECTED");
    when(rs.getArray("affected_services")).thenReturn(services);
    when(rs.getString("impacted_metrics")).thenReturn("{\"a\":1}");
    when(rs.getLong("impacted_gmv_paise")).thenReturn(100L);
    when(rs.getString("root_cause")).thenReturn(null);
    when(rs.getString("fix_applied")).thenReturn(null);
    when(rs.getString("prevention_steps")).thenReturn(null);
    when(rs.getBoolean("postmortem_filed")).thenReturn(false);
    when(rs.getTimestamp("postmortem_deadline")).thenReturn(Timestamp.from(ts));
    when(rs.getTimestamp("postmortem_reminder_sent_at")).thenReturn(null);
    when(rs.getTimestamp("detected_at")).thenReturn(Timestamp.from(ts));
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(rs.getObject("duration_minutes")).thenReturn(null);
    when(rs.getObject("created_by")).thenReturn(creator);
    when(rs.getObject("source_alert_id")).thenReturn(alertId);
    when(rs.getString("status_history"))
        .thenReturn(
            "[{\"status\":\"DETECTED\",\"updated_by\":null,\"update_message\":null,\"updated_at\":\"2026-07-24T10:00:00Z\"}]");

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    JdbcIncidentStore store = new JdbcIncidentStore(jdbc, om);
    Incident mapped = store.findById(id).orElseThrow();
    assertThat(mapped.affectedServices())
        .containsExactly(AffectedService.PAYMENT_GATEWAY, AffectedService.DISPATCH);
    assertThat(store.findBySourceAlertId(alertId)).isPresent();

    Incident incident =
        new Incident(
            id,
            "INC-20260724-001",
            "t",
            IncidentSeverity.P1,
            "d",
            IncidentStatus.INVESTIGATING,
            List.of(AffectedService.PAYMENT_GATEWAY),
            Map.of("k", "v"),
            50L,
            "root",
            "fix",
            "prevent",
            false,
            ts,
            null,
            ts,
            null,
            null,
            creator,
            alertId,
            List.of(
                new IncidentStatusEntry(IncidentStatus.DETECTED, null, null, ts),
                new IncidentStatusEntry(IncidentStatus.INVESTIGATING, "u", "m", ts)));
    assertThat(store.insert(incident).id()).isEqualTo(id);
    assertThat(store.update(incident).status()).isEqualTo(IncidentStatus.INVESTIGATING);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.list(null, null, null, null, 1, 20).total()).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(
            store
                .list(
                    IncidentStatus.DETECTED,
                    IncidentSeverity.P1,
                    ts.minusSeconds(10),
                    ts.plusSeconds(10),
                    1,
                    20)
                .incidents())
        .hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(store.countP1P2Between(ts, ts.plusSeconds(60))).isZero();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(3);
    assertThat(store.countP1P2Between(ts, ts.plusSeconds(60))).isEqualTo(3);
    assertThat(store.findResolvedAwaitingPostmortemReminder(ts)).hasSize(1);

    // blank metrics + blank history + null history/metrics
    when(rs.getArray("affected_services")).thenReturn(null);
    when(rs.getString("impacted_metrics")).thenReturn(" ");
    when(rs.getString("status_history")).thenReturn("");
    assertThat(store.findById(id).orElseThrow().affectedServices()).isEmpty();
    when(rs.getString("impacted_metrics")).thenReturn(null);
    when(rs.getString("status_history")).thenReturn(null);
    assertThat(store.findById(id).orElseThrow().impactedMetrics()).isEmpty();
    when(rs.getString("impacted_metrics")).thenReturn("{}");
    when(rs.getString("status_history"))
        .thenReturn(
            "[{\"status\":\"DETECTED\",\"updated_by\":\"u1\",\"update_message\":\"hello\",\"updated_at\":\"2026-07-24T10:00:00Z\"}]");
    assertThat(store.findById(id).orElseThrow().statusHistory().getFirst().updatedBy())
        .isEqualTo("u1");

    // non-Object[] array branch
    Array bad = mock(Array.class);
    when(bad.getArray()).thenReturn("not-an-array");
    when(rs.getArray("affected_services")).thenReturn(bad);
    assertThat(store.findById(id).orElseThrow().affectedServices()).isEmpty();

    // invalid json map / history
    when(rs.getArray("affected_services")).thenReturn(null);
    when(rs.getString("impacted_metrics")).thenReturn("{bad");
    assertThatThrownBy(() -> store.findById(id)).isInstanceOf(IllegalStateException.class);
    when(rs.getString("impacted_metrics")).thenReturn("{}");
    when(rs.getString("status_history")).thenReturn("{bad");
    assertThatThrownBy(() -> store.findById(id)).isInstanceOf(IllegalStateException.class);

    ObjectMapper failing = mock(ObjectMapper.class);
    when(failing.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    JdbcIncidentStore failStore = new JdbcIncidentStore(jdbc, failing);
    Incident bare =
        new Incident(
            id,
            "INC-20260724-001",
            "t",
            IncidentSeverity.P3,
            "d",
            IncidentStatus.DETECTED,
            List.of(),
            Map.of(),
            0L,
            null,
            null,
            null,
            false,
            null,
            null,
            ts,
            null,
            null,
            null,
            null,
            List.of());
    assertThatThrownBy(() -> failStore.insert(bare)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcSloInsertAndListHistoryBranches() throws Exception {
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getString("slo_name")).thenReturn("payment_success");
    when(rs.getDate("period_from")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 1)));
    when(rs.getDate("period_to")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 31)));
    when(rs.getBigDecimal("target_pct")).thenReturn(new BigDecimal("99"));
    when(rs.getBigDecimal("actual_pct")).thenReturn(new BigDecimal("99.4"));
    when(rs.getBoolean("compliant")).thenReturn(true);
    when(rs.getBigDecimal("error_budget_consumed_pct")).thenReturn(new BigDecimal("-40.0"));
    when(rs.getInt("incident_count")).thenReturn(1);
    when(rs.getTimestamp("recorded_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:05:00Z")));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));

    JdbcSloStore slo = new JdbcSloStore(jdbc);
    slo.insertHistory(
        new com.nammamedmate.observability_ops.domain.SloComplianceRecord(
            UUID.randomUUID(),
            "payment_success",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            new BigDecimal("99"),
            new BigDecimal("99.4"),
            true,
            new BigDecimal("-40.0"),
            1,
            Instant.parse("2026-08-01T00:05:00Z")));
    assertThat(slo.listHistory(null, null, null)).hasSize(1);
    assertThat(slo.listHistory("  ", null, null)).hasSize(1);
    assertThat(
            slo.listHistory("payment_success", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .hasSize(1);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void redisIncidentNumberLocalAndRedisPaths() {
    RedisIncidentNumberAdapter local = new RedisIncidentNumberAdapter(null);
    LocalDate day = LocalDate.of(2026, 7, 24);
    assertThat(local.next(day)).isEqualTo("INC-20260724-001");
    assertThat(local.next(day)).isEqualTo("INC-20260724-002");

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(template.opsForValue()).thenReturn(ops);
    when(ops.increment("incident:seq:20260724")).thenReturn(1L, 2L, null);
    org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(template);
    RedisIncidentNumberAdapter redis = new RedisIncidentNumberAdapter(provider);
    assertThat(redis.next(day)).isEqualTo("INC-20260724-001");
    verify(template).expire(eq("incident:seq:20260724"), any());
    assertThat(redis.next(day)).isEqualTo("INC-20260724-002");
    assertThat(redis.next(day)).isEqualTo("INC-20260724-001");

    org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> empty =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(empty.getIfAvailable()).thenReturn(null);
    RedisIncidentNumberAdapter fallback = new RedisIncidentNumberAdapter(empty);
    assertThat(fallback.next(day)).startsWith("INC-20260724-");
  }
}
