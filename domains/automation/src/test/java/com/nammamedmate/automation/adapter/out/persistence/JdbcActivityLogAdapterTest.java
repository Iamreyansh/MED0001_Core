package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.ActivityLogPort.ActivityQuery;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.ActivityStatus;
import com.nammamedmate.automation.domain.RollbackableActions;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcActivityLogAdapterTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final ObjectMapper om = new ObjectMapper();
  private final UUID id = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private final Instant now = Instant.parse("2026-07-24T08:07:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void appendFindListStatsAndFilters() throws Exception {
    stubRow();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<ActivityStats> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("last_24h")).thenReturn(10L);
              when(rs.getLong("this_week")).thenReturn(20L);
              when(rs.getLong("saved_30d")).thenReturn(15L);
              when(rs.getLong("exceptions_24h")).thenReturn(1L);
              when(rs.getLong("pending")).thenReturn(3L);
              when(rs.getTimestamp("last_action_at")).thenReturn(Timestamp.from(now));
              return ex.extractData(rs);
            });

    JdbcActivityLogAdapter adapter = new JdbcActivityLogAdapter(jdbc, om);
    UUID written =
        adapter.append(
            "suspend_entity",
            "EXECUTED",
            "ok",
            Map.of(
                "rule_id",
                UUID.randomUUID().toString(),
                "entity_type",
                "PHARMACY",
                "entity_id",
                UUID.randomUUID().toString(),
                "params",
                Map.of("reason", "x"),
                "before_state",
                Map.of("status", "ACTIVE"),
                "after_state",
                Map.of("status", "SUSPENDED")));
    assertThat(written).isNotNull();
    assertThat(
            adapter.append("rate_limit", "RATE_LIMITED", "limited", Map.of("entity_type", "ORDER")))
        .isNotNull();

    assertThat(adapter.findById(id)).isPresent();
    assertThat(adapter.existsRollbackFor(id)).isTrue();
    assertThat(
            adapter.list(
                new ActivityQuery(
                    "EXECUTED",
                    id,
                    "dispatch",
                    "order",
                    now.minusSeconds(60),
                    now,
                    RollbackableActions.FINANCIAL),
                0,
                20))
        .hasSize(1);
    assertThat(
            adapter.count(
                new ActivityQuery(null, null, null, null, null, null, Set.of("release_payout"))))
        .isEqualTo(2L);
    assertThat(adapter.count(new ActivityQuery(" ", null, " ", " ", null, null, Set.of())))
        .isEqualTo(2L);
    assertThat(adapter.count(null)).isEqualTo(2L);
    ActivityStats stats = adapter.stats(now);
    assertThat(stats.pendingApprovalsCount()).isEqualTo(3L);
    assertThat(stats.manualActionsSavedEstimate()).isEqualTo(15L);
    assertThat(stats.lastActionAt()).isEqualTo(now);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapRowNullsAndBadJson() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rule_id")).thenReturn(null);
    when(rs.getString("rule_name")).thenReturn(null);
    when(rs.getObject("workflow_execution_id")).thenReturn(null);
    when(rs.getObject("trigger_event_id")).thenReturn(null);
    when(rs.getString("trigger_event")).thenReturn(null);
    when(rs.getString("trigger_payload")).thenReturn("not-json");
    when(rs.getTimestamp("trigger_fired_at")).thenReturn(null);
    when(rs.getString("entity_type")).thenReturn("ORDER");
    when(rs.getObject("entity_id")).thenReturn(null);
    when(rs.getString("entity_name")).thenReturn(null);
    when(rs.getString("action_type")).thenReturn("x");
    when(rs.getString("action_params")).thenReturn(null);
    when(rs.getString("conditions_evaluated")).thenReturn(null);
    when(rs.getString("before_state")).thenReturn("");
    when(rs.getString("after_state")).thenReturn("nope");
    when(rs.getString("status")).thenReturn("EXECUTED");
    when(rs.getString("actor")).thenReturn("AUTOMATION");
    when(rs.getObject("override_by")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(null);
    when(rs.getTimestamp("executed_at")).thenReturn(null);
    when(rs.getObject("execution_ms")).thenReturn(null);
    when(rs.getObject("references_action_id")).thenReturn(null);
    when(rs.getString("error_message")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getBoolean("rolled_back")).thenReturn(false);
    when(rs.getObject("rollback_action_id")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<ActivityStats> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(0L);
              when(rs.getTimestamp("last_action_at")).thenReturn(null);
              return ex.extractData(rs);
            });

    JdbcActivityLogAdapter adapter = new JdbcActivityLogAdapter(jdbc, om);
    assertThat(adapter.findById(id).orElseThrow().triggeredAt()).isEqualTo(Instant.EPOCH);
    assertThat(adapter.findById(id).orElseThrow().afterState()).isEmpty();
    when(rs.getString("conditions_evaluated")).thenReturn("   ");
    assertThat(adapter.findById(id).orElseThrow().conditionsEvaluated()).isEmpty();
    when(rs.getString("conditions_evaluated")).thenReturn("not-json");
    assertThat(adapter.findById(id).orElseThrow().conditionsEvaluated()).isEmpty();
    assertThat(adapter.existsRollbackFor(id)).isFalse();
    assertThat(adapter.stats(now).lastActionAt()).isNull();

    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    JdbcActivityLogAdapter broken = new JdbcActivityLogAdapter(jdbc, boom);
    assertThat(broken.append("x", "EXECUTED", "m", Map.of())).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void countNullAndListConditionsJson() throws Exception {
    stubRow();
    when(rs.getString("conditions_evaluated")).thenReturn("[{\"a\":1}]");
    when(rs.getString("action_params")).thenReturn("{}");
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    JdbcActivityLogAdapter adapter = new JdbcActivityLogAdapter(jdbc, om);
    assertThat(adapter.count(new ActivityQuery("SIMULATED", null, null, null, null, null, null)))
        .isZero();
    assertThat(
            adapter
                .list(
                    new ActivityQuery("SIMULATED", null, "ORDERS", "ORDER", null, null, null), 0, 1)
                .getFirst()
                .status())
        .isEqualTo(ActivityStatus.EXECUTED);
  }

  @Test
  @SuppressWarnings("unchecked")
  void perRuleHealthMapsRows() throws Exception {
    when(rs.getObject("rule_id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("Auto-assign");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getLong("fire_count")).thenReturn(48L);
    when(rs.getLong("executed")).thenReturn(47L);
    when(rs.getLong("exceptions")).thenReturn(1L);
    when(rs.getObject("avg_ms")).thenReturn(387.4d);
    when(rs.getTimestamp("last_fired")).thenReturn(Timestamp.from(now));
    when(rs.getString("last_error")).thenReturn("timeout");
    when(rs.getTimestamp("last_error_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcActivityLogAdapter adapter = new JdbcActivityLogAdapter(jdbc, om);
    var row = adapter.perRuleHealth(now.minusSeconds(3600)).getFirst();
    assertThat(row.fireCount24h()).isEqualTo(48L);
    assertThat(row.successRatePct()).isEqualTo(97.9);
    when(rs.getObject("avg_ms")).thenReturn(null);
    when(rs.getTimestamp("last_fired")).thenReturn(null);
    when(rs.getTimestamp("last_error_at")).thenReturn(null);
    assertThat(adapter.perRuleHealth(now).getFirst().avgExecutionMs()).isNull();
  }

  private void stubRow() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rule_id")).thenReturn(id);
    when(rs.getString("rule_name")).thenReturn("Auto-assign");
    when(rs.getObject("workflow_execution_id")).thenReturn(null);
    when(rs.getObject("trigger_event_id")).thenReturn(id);
    when(rs.getString("trigger_event")).thenReturn("order_unassigned");
    when(rs.getString("trigger_payload")).thenReturn("{\"minutes_unassigned\":7}");
    when(rs.getTimestamp("trigger_fired_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("entity_type")).thenReturn("ORDER");
    when(rs.getObject("entity_id")).thenReturn(id);
    when(rs.getString("entity_name")).thenReturn("ORD-1");
    when(rs.getString("action_type")).thenReturn("auto_assign_rider");
    when(rs.getString("action_params")).thenReturn("{\"order_id\":\"x\"}");
    when(rs.getString("conditions_evaluated")).thenReturn("[]");
    when(rs.getString("before_state")).thenReturn("{\"order_status\":\"PLACED\"}");
    when(rs.getString("after_state")).thenReturn("{\"order_status\":\"ACCEPTED\"}");
    when(rs.getString("status")).thenReturn("EXECUTED");
    when(rs.getString("actor")).thenReturn("AUTOMATION");
    when(rs.getObject("override_by")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("executed_at")).thenReturn(Timestamp.from(now.plusSeconds(1)));
    when(rs.getObject("execution_ms")).thenReturn(420);
    when(rs.getInt("execution_ms")).thenReturn(420);
    when(rs.getObject("references_action_id")).thenReturn(null);
    when(rs.getString("error_message")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getBoolean("rolled_back")).thenReturn(false);
    when(rs.getObject("rollback_action_id")).thenReturn(null);
  }
}
