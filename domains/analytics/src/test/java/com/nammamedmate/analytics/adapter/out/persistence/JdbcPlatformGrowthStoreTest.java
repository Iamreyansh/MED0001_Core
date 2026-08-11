package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort;
import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort.Source;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.AcquisitionRow;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.CohortCell;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.GrowthTotals;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.Month1Retention;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.OrderTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.SpendRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
class JdbcPlatformGrowthStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock AcquisitionSourcePort sources;

  private final Instant from = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");
  private final LocalDate day = LocalDate.of(2026, 7, 24);
  private final UUID customerId = UUID.randomUUID();

  @Test
  @SuppressWarnings("unchecked")
  void coversReadsRefreshAndAcquisition() throws Exception {
    when(rs.getLong(anyString())).thenReturn(10L);
    when(rs.getInt(anyString())).thenReturn(5);
    when(rs.getString(anyString())).thenReturn("ORGANIC");
    when(rs.getString("cohort_week")).thenReturn("2026-W20");
    when(rs.getString("source")).thenReturn("AD");
    when(rs.getBigDecimal(anyString())).thenReturn(new BigDecimal("38.50"));
    when(rs.getBigDecimal("spend_rs")).thenReturn(new BigDecimal("100.00"));
    when(rs.getBigDecimal("retention_pct")).thenReturn(new BigDecimal("100.00"));
    when(rs.getTimestamp("computed_at")).thenReturn(Timestamp.from(from));
    when(rs.getTimestamp(1)).thenReturn(Timestamp.from(from));
    when(rs.getDate("order_date")).thenReturn(Date.valueOf(day));
    when(rs.getDate("week_start")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 20)));
    when(rs.getObject("customer_id")).thenReturn(customerId);
    when(rs.getObject(1)).thenReturn(customerId);
    when(rs.next()).thenReturn(true);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              return ex.extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
        .thenReturn(3);
    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient().when(jdbc.update(anyString(), anyString())).thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(Date.class))).thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    when(sources.sourceForCustomer(customerId)).thenReturn(Source.ORGANIC);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);

    GrowthTotals live = store.liveGrowth(from, to);
    assertThat(live.activeCustomers()).isEqualTo(10L);
    assertThat(store.aggregatedGrowth(day, day).newCustomers()).isEqualTo(10L);

    List<CohortCell> matrix = store.cohortMatrix(12);
    assertThat(matrix).hasSize(1);
    assertThat(store.cohortLastComputedAt()).contains(from);

    Optional<Month1Retention> m1 = store.month1Retention(LocalDate.of(2026, 7, 24));
    assertThat(m1).isPresent();

    List<AcquisitionRow> liveAcq = store.liveAcquisition(from, to);
    assertThat(liveAcq).isNotEmpty();
    assertThat(store.aggregatedAcquisition(day, day)).hasSize(1);

    List<SpendRow> spend = store.campaignSpend(day, day);
    assertThat(spend.getFirst().source()).isEqualTo("AD");

    List<OrderTrendPoint> daily = store.orderTrendDaily(from, to);
    assertThat(daily.getFirst().totalOrders()).isEqualTo(10L);
    List<OrderTrendPoint> weekly = store.orderTrendWeekly(from, to);
    assertThat(weekly.getFirst().date()).isEqualTo(LocalDate.of(2026, 7, 20));

    store.refreshCohortRetention(1, from);
    verify(jdbc)
        .update(eq("DELETE FROM analytics_cohort_retention WHERE cohort_week = ?"), anyString());

    store.refreshAcquisitionDaily(day, day);
    verify(jdbc)
        .update(
            eq("DELETE FROM analytics_acquisition_daily WHERE snapshot_date = ?"), any(Date.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyGrowthAndNoMonth1() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(null);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    assertThat(store.liveGrowth(from, to).activeCustomers()).isZero();
    assertThat(store.cohortLastComputedAt()).isEmpty();
    // Recent month incomplete → continue; older months have no rows → empty
    assertThat(store.month1Retention(LocalDate.of(2026, 7, 5))).isEmpty();
    assertThat(store.month1Retention(LocalDate.of(2026, 1, 15))).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshEmptyCohortElapsedAndNullCount() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
        .thenReturn(null);
    lenient().when(jdbc.update(anyString(), anyString())).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    // Empty members → cohortSize 0 path; still inserts elapsed 0
    store.refreshCohortRetention(1, from);
    verify(jdbc)
        .update(eq("DELETE FROM analytics_cohort_retention WHERE cohort_week = ?"), anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void liveAcquisitionSkipsEmptySourcesAndRefreshSkipsZeros() throws Exception {
    UUID other = UUID.randomUUID();
    when(rs.getObject("customer_id")).thenReturn(customerId, other);
    when(rs.getLong("orders")).thenReturn(2L, 3L);
    when(rs.getLong("gmv_paise")).thenReturn(100L, 200L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1));
            });
    when(sources.sourceForCustomer(customerId)).thenReturn(Source.AD);
    when(sources.sourceForCustomer(other)).thenReturn(Source.ORGANIC);
    lenient().when(jdbc.update(anyString(), any(Date.class))).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    List<AcquisitionRow> rows = store.liveAcquisition(from, to);
    assertThat(rows).extracting(AcquisitionRow::source).containsExactly("ORGANIC", "AD");

    // No customers → all sources zero → refresh skips inserts after delete
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenReturn(List.of());
    store.refreshAcquisitionDaily(day, day);
    verify(jdbc)
        .update(
            eq("DELETE FROM analytics_acquisition_daily WHERE snapshot_date = ?"), any(Date.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void cohortLastComputedNullTimestampAndNullRowsList() throws Exception {
    when(rs.next()).thenReturn(true);
    when(rs.getTimestamp(1)).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              return ex.extractData(rs);
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(null);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    assertThat(store.cohortLastComputedAt()).isEmpty();
    assertThat(store.liveGrowth(from, to).activeCustomers()).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshCohortNullAndNonNullRetainedCount() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(customerId));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
        .thenReturn(null)
        .thenReturn(2);
    lenient().when(jdbc.update(anyString(), anyString())).thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    store.refreshCohortRetention(3, from);
    verify(jdbc, org.mockito.Mockito.atLeast(2))
        .queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any());
  }

  @Test
  void cohortLastComputedWhenNoRows() throws Exception {
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    JdbcPlatformGrowthStore store = new JdbcPlatformGrowthStore(jdbc, sources);
    assertThat(store.cohortLastComputedAt()).isEmpty();
  }
}
