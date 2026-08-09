package com.nammamedmate.pos.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pos.application.port.out.KhataStore;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class JdbcKhataCoverageFinalTest {

  @Mock JdbcTemplate jdbc;
  JdbcKhataStore store;
  UUID pharmacy = UUID.randomUUID();
  UUID c1 = UUID.randomUUID();
  UUID c2 = UUID.randomUUID();
  Instant now = Instant.parse("2026-07-24T12:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcKhataStore(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
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
            any()))
        .thenReturn(1);
  }

  @Test
  void kpiNullSumsAndKnownFalse() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject(1)).thenReturn(c1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(c1))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), eq(c1))).thenReturn(null);

    KhataStore.KpiSnapshot kpi =
        store.kpi(pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
    assertThat(kpi.collectedThisMonthPaise()).isZero();
    assertThat(kpi.allTimeCreditGivenPaise()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(null);
    assertThat(store.customerKnownToPharmacy(pharmacy, c1)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    assertThat(store.customerKnownToPharmacy(pharmacy, c1)).isFalse();
  }

  @Test
  void ledgerMapperInvokedAndNameFallback() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(c1)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              if (sql.contains("running_balance_paise")) {
                when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                when(rs.getString("type")).thenReturn("DEBIT");
                when(rs.getLong("amount_paise")).thenReturn(100L);
                when(rs.getString("reference_number")).thenReturn("INV");
                when(rs.getLong("running_balance_paise")).thenReturn(100L);
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });
    assertThat(store.ledgerDesc(pharmacy, c1)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(100L);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(c1)))
        .thenReturn(List.of(new KhataStore.CustomerInfo(c1, null, "+91")));
    assertThat(
            store
                .recordRepayment(pharmacy, c1, 10L, "CASH", null, null, UUID.randomUUID(), now)
                .customerName())
        .isEqualTo("+91");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(c1))).thenReturn(List.of());
    assertThat(
            store
                .recordRepayment(pharmacy, c1, 10L, "CASH", null, null, UUID.randomUUID(), now)
                .customerName())
        .isEqualTo("Customer");
  }

  @Test
  void sortTieBreakersAndCountNull() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (!sql.contains("GROUP BY")) {
                return List.of();
              }
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs1 = mock(ResultSet.class);
              when(rs1.getObject("customer_id")).thenReturn(c1);
              when(rs1.getString("name")).thenReturn("A");
              when(rs1.getString("phone")).thenReturn("+91");
              when(rs1.getLong("outstanding")).thenReturn(100L);
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("customer_id")).thenReturn(c2);
              when(rs2.getString("name")).thenReturn("B");
              when(rs2.getString("phone")).thenReturn("+92");
              when(rs2.getLong("outstanding")).thenReturn(100L);
              return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), any())).thenReturn(0L);

    assertThat(store.listOutstanding(pharmacy, false, "outstanding_asc", null, 10, 0)).hasSize(2);
    assertThat(store.listOutstanding(pharmacy, false, "oldest_bill", null, 10, 0)).hasSize(2);
    assertThat(store.listOutstanding(pharmacy, false, "outstanding_desc", null, 10, 0)).hasSize(2);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.countPaymentHistory(pharmacy, null, null, null, null)).isZero();
    assertThat(store.paymentHistoryTotalPaise(pharmacy, null, null, null, null)).isZero();
  }
}
