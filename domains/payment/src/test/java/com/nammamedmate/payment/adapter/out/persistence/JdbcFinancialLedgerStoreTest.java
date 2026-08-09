package com.nammamedmate.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.DayKpis;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerPage;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerRow;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcFinancialLedgerStoreTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void listExportAndKpis() throws Exception {
    JdbcFinancialLedgerStore store = new JdbcFinancialLedgerStore(jdbc);
    UUID id = UUID.randomUUID();
    UUID ref = UUID.randomUUID();
    Instant created = Instant.parse("2026-07-24T13:15:00Z");

    when(jdbc.queryForObject(anyString(), ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entry_type")).thenReturn("ORDER_GMV");
              when(rs.getObject("reference_id")).thenReturn(ref);
              when(rs.getString("reference_type")).thenReturn("PAYMENT");
              when(rs.getLong("credit_paise")).thenReturn(100L);
              when(rs.getLong("debit_paise")).thenReturn(0L);
              when(rs.getLong("running_balance_paise")).thenReturn(100L);
              when(rs.getString("description")).thenReturn("gmv");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
              return List.of(mapper.mapRow(rs, 0));
            });

    LedgerPage page =
        store.list(
            new String[] {"ORDER_GMV"},
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            1,
            50,
            false);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().getFirst().entryType()).isEqualTo("ORDER_GMV");

    List<LedgerRow> all =
        store.listAllForExport(new String[] {"ORDER_GMV", "COMMISSION"}, null, null);
    assertThat(all).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<DayKpis> mapper = inv.getArgument(1);
              when(rs.getLong("gmv")).thenReturn(10L);
              when(rs.getLong("commission")).thenReturn(2L);
              when(rs.getLong("gateway_fee")).thenReturn(1L);
              return List.of(mapper.mapRow(rs, 0));
            });
    DayKpis kpis =
        store.dayKpis(Instant.parse("2026-07-24T00:00:00Z"), Instant.parse("2026-07-25T00:00:00Z"));
    assertThat(kpis.gmvTodayPaise()).isEqualTo(10L);
    assertThat(kpis.commissionTodayPaise()).isEqualTo(2L);
    assertThat(kpis.gatewayFeeTodayPaise()).isEqualTo(1L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(
            store
                .dayKpis(
                    Instant.parse("2026-07-24T00:00:00Z"), Instant.parse("2026-07-25T00:00:00Z"))
                .gmvTodayPaise())
        .isZero();

    when(jdbc.queryForObject(anyString(), ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entry_type")).thenReturn("COMMISSION");
              when(rs.getObject("reference_id")).thenReturn(ref);
              when(rs.getString("reference_type")).thenReturn("PAYMENT");
              when(rs.getLong("credit_paise")).thenReturn(10L);
              when(rs.getLong("debit_paise")).thenReturn(0L);
              when(rs.getLong("running_balance_paise")).thenReturn(10L);
              when(rs.getString("description")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    LedgerPage emptyTypes = store.list(new String[0], null, null, 1, 20, true);
    assertThat(emptyTypes.total()).isZero();
    assertThat(emptyTypes.rows().getFirst().createdAt()).isNull();

    when(jdbc.queryForObject(anyString(), ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(null, null, null, 1, 20, true).total()).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entry_type")).thenReturn("A");
              when(rs.getObject("reference_id")).thenReturn(ref);
              when(rs.getString("reference_type")).thenReturn("PAYMENT");
              when(rs.getLong("credit_paise")).thenReturn(1L);
              when(rs.getLong("debit_paise")).thenReturn(0L);
              when(rs.getLong("running_balance_paise")).thenReturn(1L);
              when(rs.getString("description")).thenReturn("x");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(2L);
    assertThat(
            store
                .list(
                    new String[] {"A", "B"},
                    null,
                    Instant.parse("2026-08-01T00:00:00Z"),
                    1,
                    10,
                    true)
                .total())
        .isEqualTo(2L);
  }
}
