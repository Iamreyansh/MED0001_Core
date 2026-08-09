package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.CohortRetentionRow;
import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class JdbcSaasAnalyticsStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasAnalyticsStore store = new JdbcSaasAnalyticsStore(jdbc);
    Instant now = Instant.parse("2026-07-01T00:00:00Z");
    LocalDate july = LocalDate.of(2026, 7, 1);

    when(rs.getDate("metric_month")).thenReturn(Date.valueOf(july));
    when(rs.getDate("cohort_month")).thenReturn(Date.valueOf(LocalDate.of(2026, 1, 1)));
    when(rs.getLong(anyString())).thenReturn(100L);
    when(rs.getInt(anyString())).thenReturn(2);
    when(rs.getBigDecimal(anyString())).thenReturn(new BigDecimal("100.00"));
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getString("plan")).thenReturn("STARTER");

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(42L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(42L);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
        .thenReturn(42L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(42L);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(3);
    lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(3);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(3);
    lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(10);
    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient()
        .when(
            jdbc.update(
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
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
        .thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

    assertThat(store.findMetrics(july)).isPresent();
    assertThat(store.listMetrics(LocalDate.of(2026, 1, 1), july)).hasSize(1);
    SaasMetricsSnapshot snap = store.findMetrics(july).orElseThrow();
    store.upsertMetrics(snap);
    verify(jdbc)
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
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());

    assertThat(store.sumActiveMrrPaise(null)).isEqualTo(42L);
    assertThat(store.sumActiveMrrPaise("")).isEqualTo(42L);
    assertThat(store.sumActiveMrrPaise("STARTER")).isEqualTo(42L);
    assertThat(store.countPayingAccounts(null)).isEqualTo(42L);
    assertThat(store.countPayingAccounts(" ")).isEqualTo(42L);
    assertThat(store.countPayingAccounts("STARTER")).isEqualTo(42L);
    assertThat(store.mrrByPlan(null)).hasSize(1);
    assertThat(store.mrrByPlan("")).hasSize(1);
    assertThat(store.mrrByPlan("STARTER")).hasSize(1);
    assertThat(store.sumNewLogoMrrPaise(july, july.plusMonths(1))).isEqualTo(42L);
    assertThat(store.countNewLogos(july, july.plusMonths(1))).isEqualTo(3);
    Instant a = now;
    Instant b = now.plusSeconds(86400);
    assertThat(store.sumChurnMrrPaise(a, b)).isEqualTo(42L);
    assertThat(store.countChurnedLogos(a, b)).isEqualTo(3);
    assertThat(store.sumExpansionMrrPaise(a, b)).isEqualTo(42L);
    assertThat(store.countExpansionAccounts(a, b)).isEqualTo(3);
    assertThat(store.sumContractionMrrPaise(a, b)).isEqualTo(42L);
    assertThat(store.countContractionAccounts(a, b)).isEqualTo(3);
    assertThat(store.smSpendPaise(july)).isEqualTo(42L);
    assertThat(store.sumSmSpendPaise(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 1)))
        .isEqualTo(42L);

    store.replaceCohortRetention(
        List.of(
            new CohortRetentionRow(LocalDate.of(2026, 1, 1), 0, 10, 10, new BigDecimal("100.00"))));
    assertThat(store.listCohortRetention(LocalDate.of(2026, 1, 1), july)).hasSize(1);

    // computeLive: distinct cohorts via query(sql, mapper, from, to)
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              Object mapped = mapper.mapRow(rs, 0);
              if (mapped instanceof LocalDate) {
                return List.of(mapped);
              }
              return List.of(mapped);
            });
    assertThat(store.computeLiveCohortRetention(LocalDate.of(2026, 1, 1), july, july)).isNotEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.findMetrics(july)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.sumActiveMrrPaise(null)).isZero();
    assertThat(store.countPayingAccounts(null)).isZero();
    assertThat(store.sumNewLogoMrrPaise(july, july.plusMonths(1))).isZero();
    assertThat(store.countNewLogos(july, july.plusMonths(1))).isZero();
    assertThat(store.sumChurnMrrPaise(a, b)).isZero();
    assertThat(store.countChurnedLogos(a, b)).isZero();
    assertThat(store.sumExpansionMrrPaise(a, b)).isZero();
    assertThat(store.countExpansionAccounts(a, b)).isZero();
    assertThat(store.sumContractionMrrPaise(a, b)).isZero();
    assertThat(store.countContractionAccounts(a, b)).isZero();
    assertThat(store.smSpendPaise(july)).isZero();
    assertThat(store.sumSmSpendPaise(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 1))).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(List.of(LocalDate.of(2025, 1, 1)));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(
            store.computeLiveCohortRetention(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1), LocalDate.of(2026, 7, 1)))
        .hasSize(13);
  }
}
