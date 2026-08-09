package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.AccountHealthScore;
import com.nammamedmate.crm.domain.AccountHealthSnapshot;
import com.nammamedmate.crm.domain.HealthBand;
import com.nammamedmate.crm.domain.SavePlay;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcSaasAccountHealthStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;
  @Mock Connection connection;
  @Mock PreparedStatement ps;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasAccountHealthStore store = new JdbcSaasAccountHealthStore(jdbc);
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T03:00:00Z");
    LocalDate scoreDate = LocalDate.of(2026, 7, 24);

    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("account_id")).thenReturn(accountId);
    when(rs.getDouble("overall_score")).thenReturn(42.0);
    when(rs.getDouble("product_usage_score")).thenReturn(55.0);
    when(rs.getDouble("billing_health_score")).thenReturn(70.0);
    when(rs.getDouble("support_satisfaction_score")).thenReturn(30.0);
    when(rs.getDouble("business_performance_score")).thenReturn(20.0);
    when(rs.getString("health_band")).thenReturn(HealthBand.AT_RISK);
    when(rs.getArray("risk_factors")).thenReturn(sqlArray);
    when(rs.getArray("recommended_actions")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new String[] {"Low adoption"});
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getString("pharmacy_name")).thenReturn("Apollo");
    when(rs.getString("plan")).thenReturn("RETAIL_PRO");
    when(rs.getLong("mrr_paise")).thenReturn(149900L);
    when(rs.getDate("renewal_date")).thenReturn(Date.valueOf(scoreDate));
    when(rs.next()).thenReturn(true, false);
    when(rs.getDouble("avg_score")).thenReturn(68.4);
    when(rs.getDouble("healthy_pct")).thenReturn(62.0);
    when(rs.getDouble("moderate_pct")).thenReturn(24.0);
    when(rs.getLong("at_risk_count")).thenReturn(32L);
    when(rs.getLong("churning_count")).thenReturn(8L);

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              return ex.extractData(rs);
            });
    lenient()
        .when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              return ex.extractData(rs);
            });
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(2L);
    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    lenient()
        .when(
            jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(sqlArray);
    when(connection.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(1);
    when(jdbc.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            inv -> {
              ConnectionCallback<?> cb = inv.getArgument(0);
              return cb.doInConnection(connection);
            });

    assertThat(store.findByAccountId(accountId)).isPresent();
    AccountHealthScore score =
        new AccountHealthScore(
            UUID.randomUUID(),
            accountId,
            42,
            55,
            70,
            30,
            20,
            HealthBand.AT_RISK,
            List.of("Low adoption"),
            List.of("Train"),
            now);
    store.upsert(score);
    store.upsert(
        new AccountHealthScore(
            score.id(), accountId, 42, 55, 70, 30, 20, HealthBand.AT_RISK, null, List.of(), now));
    store.upsertSnapshot(
        new AccountHealthSnapshot(
            UUID.randomUUID(), accountId, scoreDate, 42, HealthBand.AT_RISK, 55, 70, 30, 20));
    SavePlay play =
        store.insertSavePlay(
            new SavePlay(UUID.randomUUID(), accountId, "CALL", "ok", "n", UUID.randomUUID(), now));
    assertThat(play.actionType()).isEqualTo("CALL");
    assertThat(store.maxSavePlayAt(accountId)).isEqualTo(now);
    assertThat(store.countOpenSavePlayAccounts()).isEqualTo(5L);
    assertThat(store.listAtRisk(null, 0, 20)).hasSize(1);
    assertThat(store.listAtRisk(HealthBand.AT_RISK, 0, 20)).hasSize(1);
    assertThat(store.countAtRisk(null)).isEqualTo(5L);
    assertThat(store.countAtRisk(HealthBand.CHURNING)).isEqualTo(2L);
    assertThat(store.sumMrrAtRiskPaise()).isEqualTo(5L);
    assertThat(store.kpis().atRiskCount()).isEqualTo(32L);

    when(rs.getArray("risk_factors")).thenReturn(null);
    when(rs.getArray("recommended_actions")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new Object[] {"x"});
    when(rs.getDate("renewal_date")).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    assertThat(store.countAtRisk(null)).isZero();
    assertThat(store.countAtRisk(HealthBand.AT_RISK)).isZero();
    assertThat(store.sumMrrAtRiskPaise()).isZero();
    assertThat(store.countOpenSavePlayAccounts()).isZero();
    assertThat(store.kpis().avgHealthScore()).isEqualTo(0.0);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.maxSavePlayAt(accountId)).isNull();
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return ex.extractData(rs);
            });
    assertThat(store.maxSavePlayAt(accountId)).isNull();

    when(rs.getArray("risk_factors")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getArray("risk_factors")).thenReturn(sqlArray);
              when(rs.getArray("recommended_actions")).thenReturn(null);
              when(sqlArray.getArray()).thenReturn(new Integer[] {1});
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByAccountId(accountId)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getDate("renewal_date")).thenReturn(null);
              when(rs.getTimestamp("last_save_play_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listAtRisk(null, 0, 5).getFirst().renewalDate()).isNull();
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).execute(any(ConnectionCallback.class));
  }
}
