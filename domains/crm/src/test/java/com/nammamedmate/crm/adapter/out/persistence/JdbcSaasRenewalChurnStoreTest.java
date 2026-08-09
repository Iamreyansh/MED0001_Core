package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.ChurnSurvey;
import com.nammamedmate.crm.domain.RenewalRiskLevel;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Date;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcSaasRenewalChurnStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasRenewalChurnStore store = new JdbcSaasRenewalChurnStore(jdbc);
    UUID accountId = Ids.newId();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    Instant windowEnd = now.plusSeconds(86400L * 30);

    when(rs.getObject("account_id")).thenReturn(accountId);
    when(rs.getObject("subscription_id")).thenReturn(Ids.newId());
    when(rs.getString("pharmacy_name")).thenReturn("Apollo");
    when(rs.getString("plan")).thenReturn("STARTER");
    when(rs.getLong("mrr_paise")).thenReturn(69900L);
    when(rs.getDate("renewal_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 8, 5)));
    when(rs.getBoolean("auto_renew")).thenReturn(true);
    when(rs.getDouble("health_score")).thenReturn(42.0);
    when(rs.getDouble("overall_score")).thenReturn(42.0);
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getString("reason")).thenReturn("PRICE");
    when(rs.getLong("cnt")).thenReturn(3L);
    when(rs.getDate("cohort_month")).thenReturn(Date.valueOf(LocalDate.of(2026, 1, 1)));
    when(rs.getLong("cohort_size")).thenReturn(100L);
    when(rs.getLong("m1")).thenReturn(2L);
    when(rs.getLong("m3")).thenReturn(5L);
    when(rs.getLong("m6")).thenReturn(9L);

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(5L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(5L);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
        .thenReturn(5L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    lenient().when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    store.ensureCohort(accountId, LocalDate.of(2026, 7, 1), now);
    assertThat(
            store.insertSurvey(
                new ChurnSurvey(Ids.newId(), accountId, "PRICE", "n", Ids.newId(), now)))
        .extracting(ChurnSurvey::reason)
        .isEqualTo("PRICE");

    assertThat(store.listUpcoming(now, windowEnd, null, null, 0, 20)).isNotEmpty();
    assertThat(store.listUpcoming(now, windowEnd, RenewalRiskLevel.HIGH, null, 0, 20)).isNotEmpty();
    assertThat(store.listUpcoming(now, windowEnd, RenewalRiskLevel.MEDIUM, null, 0, 20))
        .isNotEmpty();
    assertThat(store.listUpcoming(now, windowEnd, RenewalRiskLevel.LOW, null, 0, 20)).isNotEmpty();
    assertThat(store.listUpcoming(now, windowEnd, null, Ids.newId(), 0, 20)).isNotEmpty();
    assertThat(store.listUpcoming(now, windowEnd, "UNKNOWN", null, 0, 20)).isNotEmpty();

    when(rs.getDate("renewal_date")).thenReturn(null);
    when(rs.getTimestamp(anyString())).thenReturn(null);
    assertThat(store.listUpcoming(now, windowEnd, null, null, 0, 20).getFirst().renewalDate())
        .isNull();
    assertThat(store.churnLog(now, windowEnd, 10).getFirst().churnedAt()).isNull();

    assertThat(store.countUpcoming(now, windowEnd, null, null)).isEqualTo(5L);
    assertThat(store.countUpcoming(now, windowEnd, RenewalRiskLevel.HIGH, Ids.newId()))
        .isEqualTo(5L);
    assertThat(store.countRenewing(now, windowEnd)).isEqualTo(5L);
    assertThat(store.sumMrrAtRiskPaise(now, windowEnd)).isEqualTo(5L);
    assertThat(store.countChurnedLogos(now, windowEnd)).isEqualTo(5L);
    assertThat(store.countStartOfPeriodLogos(now, windowEnd)).isEqualTo(10L);
    assertThat(store.sumMrrChurnedPaise(now, windowEnd)).isEqualTo(5L);
    assertThat(store.countSavePlaysSince(now)).isEqualTo(5L);
    assertThat(store.churnReasons(now, windowEnd)).isNotEmpty();
    assertThat(store.cohortChurnRates(LocalDate.of(2026, 7, 24))).isNotEmpty();
    assertThat(store.cohortChurnRates(LocalDate.of(2026, 1, 15)).getFirst().month1ChurnPct())
        .isNull();
    when(rs.getLong("cohort_size")).thenReturn(0L);
    assertThat(store.cohortChurnRates(LocalDate.of(2026, 7, 24)).getFirst().month1ChurnPct())
        .isEqualByComparingTo("0.0");
    assertThat(store.countChurnedWithLowAdoption(now, windowEnd)).isEqualTo(5L);
    assertThat(store.countChurnedWithMissedPayments(now, windowEnd)).isEqualTo(5L);
    assertThat(store.findWinbackDue(now, windowEnd)).isNotEmpty();
    assertThat(store.findAtRiskRenewals(now, windowEnd)).isNotEmpty();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(store.countUpcoming(now, windowEnd, null, null)).isZero();
    assertThat(store.sumMrrAtRiskPaise(now, windowEnd)).isZero();
    assertThat(store.countChurnedLogos(now, windowEnd)).isZero();
    assertThat(store.countStartOfPeriodLogos(now, windowEnd)).isZero();
    assertThat(store.sumMrrChurnedPaise(now, windowEnd)).isZero();
    assertThat(store.countSavePlaysSince(now)).isZero();
    assertThat(store.countChurnedWithLowAdoption(now, windowEnd)).isZero();
    assertThat(store.countChurnedWithMissedPayments(now, windowEnd)).isZero();

    verify(jdbc).update(anyString(), any(), any(), any());
  }
}
