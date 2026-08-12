package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.domain.CircuitStatus;
import com.nammamedmate.automation.domain.KillSwitchAction;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class JdbcHealthAdaptersTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:50:00Z"), ZoneOffset.UTC);
  private final ObjectMapper om = new ObjectMapper();
  private final UUID admin = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Test
  @SuppressWarnings("unchecked")
  void killSwitchCacheSetAndLastChange() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(List.of("PAUSED"))
        .thenReturn(List.of());
    JdbcKillSwitchAdapter adapter = new JdbcKillSwitchAdapter(jdbc, clock);
    assertThat(adapter.status()).isEqualTo(KillSwitchStatus.PAUSED);
    assertThat(adapter.status()).isEqualTo(KillSwitchStatus.PAUSED);
    verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));

    adapter.setStatus(KillSwitchStatus.PAUSED, admin, "stop");
    adapter.setStatus(null, admin, "resume-default");
    verify(jdbc, times(2)).update(anyString(), any(), any(), any());
    verify(jdbc, times(2)).update(anyString(), any(), any(), any(), any(), any());

    when(rs.getString("action")).thenReturn("RESUME");
    when(rs.getObject("changed_by")).thenReturn(admin);
    when(rs.getString("reason")).thenReturn("ok");
    when(rs.getTimestamp("changed_at")).thenReturn(Timestamp.from(clock.instant()));
    when(rs.getString("email")).thenReturn("admin@nammamedmate.in");
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.lastChange().orElseThrow().changedByLabel())
        .isEqualTo("admin@nammamedmate.in");
    assertThat(adapter.lastChange().orElseThrow().action()).isEqualTo(KillSwitchAction.RESUME);

    when(rs.getString("email")).thenReturn(null);
    when(rs.getTimestamp("changed_at")).thenReturn(null);
    assertThat(adapter.lastChange().orElseThrow().changedByLabel()).isEqualTo(admin.toString());
    when(rs.getString("email")).thenReturn(" ");
    assertThat(adapter.lastChange().orElseThrow().changedByLabel()).isEqualTo(admin.toString());

    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    assertThat(new JdbcKillSwitchAdapter(jdbc).lastChange()).isEmpty();
    java.util.concurrent.atomic.AtomicReference<Instant> tick =
        new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
    Clock moving =
        new Clock() {
          @Override
          public Instant instant() {
            return tick.get();
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }
        };
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(List.of("PAUSED"))
        .thenReturn(List.of("ACTIVE"));
    JdbcKillSwitchAdapter cached = new JdbcKillSwitchAdapter(jdbc, moving);
    assertThat(cached.status()).isEqualTo(KillSwitchStatus.PAUSED);
    tick.set(clock.instant().plusSeconds(6));
    assertThat(cached.status()).isEqualTo(KillSwitchStatus.ACTIVE);
    assertThat(new JdbcKillSwitchAdapter(jdbc, null).status()).isEqualTo(KillSwitchStatus.ACTIVE);
  }

  @Test
  @SuppressWarnings("unchecked")
  void circuitBreakerAcquireListAndConfig() throws Exception {
    stubCircuitRow("apply_wallet_credit", "CLOSED", 0, null, null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(10);
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(2);
              when(rs.getString(1)).thenReturn("30");
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcCircuitBreakerAdapter adapter = new JdbcCircuitBreakerAdapter(jdbc, clock);
    assertThat(adapter.tryAcquire("apply_wallet_credit")).isTrue();
    assertThat(adapter.tryAcquire(" ")).isTrue();
    assertThat(adapter.tryAcquire(null)).isTrue();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(50);
    assertThat(adapter.tryAcquire("apply_wallet_credit")).isFalse();

    stubCircuitRow(
        "apply_wallet_credit",
        "OPEN",
        52,
        Timestamp.from(Instant.parse("2026-07-24T09:30:00Z")),
        Timestamp.from(Instant.parse("2026-07-24T10:20:00Z")));
    assertThat(adapter.tryAcquire("apply_wallet_credit")).isFalse();

    stubCircuitRow(
        "apply_wallet_credit",
        "OPEN",
        52,
        Timestamp.from(Instant.parse("2026-07-24T09:00:00Z")),
        Timestamp.from(Instant.parse("2026-07-24T09:30:00Z")));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    assertThat(adapter.tryAcquire("apply_wallet_credit")).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(rs.getInt("live_fires")).thenReturn(24);
    assertThat(adapter.list()).hasSize(1);

    stubCircuitRow(
        "apply_wallet_credit",
        "OPEN",
        52,
        Timestamp.from(Instant.parse("2026-07-24T09:00:00Z")),
        Timestamp.from(Instant.parse("2026-07-24T09:30:00Z")));
    when(rs.getInt("live_fires")).thenReturn(52);
    assertThat(adapter.list().getFirst().status()).isEqualTo(CircuitStatus.CLOSED);

    stubCircuitRow("auto_assign_rider", "CLOSED", 24, null, null);
    when(rs.getInt("live_fires")).thenReturn(24);
    when(rs.getTimestamp("updated_at")).thenReturn(null);
    assertThat(adapter.list().getFirst().status()).isEqualTo(CircuitStatus.CLOSED);

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(new JdbcCircuitBreakerAdapter(jdbc).tryAcquire("x")).isTrue();
    assertThat(new JdbcCircuitBreakerAdapter(jdbc, null).tryAcquire("y")).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void circuitResetMinutesEmptyNullAndThrow() throws Exception {
    stubCircuitRow("apply_wallet_credit", "CLOSED", 0, null, null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(50);

    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenThrow(new RuntimeException("missing"));
    assertThat(new JdbcCircuitBreakerAdapter(jdbc, clock).tryAcquire("apply_wallet_credit"))
        .isFalse();

    stubCircuitRow("apply_wallet_credit", "CLOSED", 0, null, null);
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());
    assertThat(new JdbcCircuitBreakerAdapter(jdbc, clock).tryAcquire("apply_wallet_credit"))
        .isFalse();

    stubCircuitRow("apply_wallet_credit", "CLOSED", 0, null, null);
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(null);
    assertThat(new JdbcCircuitBreakerAdapter(jdbc, clock).tryAcquire("apply_wallet_credit"))
        .isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void deferredEnqueueListDeleteAndBadJson() throws Exception {
    JdbcDeferredExecutionAdapter adapter = new JdbcDeferredExecutionAdapter(jdbc, om);
    UUID id = UUID.fromString("44444444-4444-4444-8444-444444444444");
    adapter.enqueue(id, "release_payout", Map.of("a", 1), Map.of("b", 2));
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any());

    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("approval_id")).thenReturn(id);
    when(rs.getString("action_type")).thenReturn("release_payout");
    when(rs.getString("action_params")).thenReturn("{\"a\":1}");
    when(rs.getString("execution_context")).thenReturn("not-json");
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.listAll().getFirst().executionContext()).isEmpty();
    when(rs.getString("action_params")).thenReturn(" ");
    when(rs.getString("execution_context")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(clock.instant()));
    assertThat(adapter.listAll().getFirst().actionParams()).isEmpty();

    adapter.delete(id);
    verify(jdbc).update(eq("DELETE FROM automation_deferred_executions WHERE id = ?"), eq(id));

    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    new JdbcDeferredExecutionAdapter(jdbc, boom).enqueue(id, "x", null, null);
  }

  private void stubCircuitRow(
      String action, String status, int fires, Timestamp opened, Timestamp reset) throws Exception {
    when(rs.getString("action_type")).thenReturn(action);
    when(rs.getInt("threshold_per_hour")).thenReturn(50);
    when(rs.getString("circuit_status")).thenReturn(status);
    when(rs.getInt("fires_last_hour")).thenReturn(fires);
    when(rs.getTimestamp("opened_at")).thenReturn(opened);
    when(rs.getTimestamp("reset_at")).thenReturn(reset);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(clock.instant()));
  }
}
