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
class JdbcKhataStoreTest {

  @Mock JdbcTemplate jdbc;
  JdbcKhataStore store;
  UUID pharmacy = UUID.randomUUID();
  UUID customer = UUID.randomUUID();
  Instant now = Instant.parse("2026-07-24T12:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcKhataStore(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
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
  void outstandingAndDefaultLimit() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(12_500L);
    assertThat(store.outstandingPaise(pharmacy, customer)).isEqualTo(12_500L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.creditLimitPaise(pharmacy, customer))
        .isEqualTo(KhataStore.DEFAULT_CREDIT_LIMIT_PAISE);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(List.of(1_000_000L));
    assertThat(store.creditLimitPaise(pharmacy, customer)).isEqualTo(1_000_000L);
  }

  @Test
  void postCreditSaleAndRepayment() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(List.of("INV-2026-07-000001"));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
    store.postCreditSale(customer, UUID.randomUUID(), 5000L, pharmacy);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(5000L);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(3);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(customer)))
        .thenReturn(List.of(new KhataStore.CustomerInfo(customer, "A", "+91")));

    String receipt =
        store.recordCreditRepayment(
            customer, UUID.randomUUID(), 2000L, pharmacy, "CASH", null, "n", UUID.randomUUID());
    assertThat(receipt).startsWith("RCPT-");
  }

  @Test
  void unpaidBillsFifoAndAging() throws Exception {
    UUID inv = UUID.randomUUID();
    Instant old = Instant.parse("2026-05-01T00:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(customer)))
        .thenAnswer(
            invCall -> {
              RowMapper<?> mapper = invCall.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("invoice_id")).thenReturn(inv);
              when(rs.getString("reference_number")).thenReturn("INV-1");
              when(rs.getLong("amount_paise")).thenReturn(10_000L);
              when(rs.getTimestamp("invoice_at")).thenReturn(Timestamp.from(old));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), eq(customer)))
        .thenReturn(3_000L);

    List<KhataStore.UnpaidBillRow> unpaid =
        store.unpaidBills(pharmacy, customer, LocalDate.of(2026, 7, 24));
    assertThat(unpaid).hasSize(1);
    assertThat(unpaid.getFirst().amountPaise()).isEqualTo(7_000L);
    assertThat(unpaid.getFirst().daysSince()).isGreaterThan(30);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy))).thenReturn(List.of(customer));
    KhataStore.AgingBuckets aging = store.aging(pharmacy, LocalDate.of(2026, 7, 24));
    assertThat(aging.overdue60PlusPaise() + aging.overdue31To60Paise()).isEqualTo(7_000L);
  }

  @Test
  void findCustomerAndKnown() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(customer)))
        .thenReturn(List.of(new KhataStore.CustomerInfo(customer, "A", "+91")));
    assertThat(store.findCustomer(customer)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(1L);
    assertThat(store.customerKnownToPharmacy(pharmacy, customer)).isTrue();

    store.ensureCustomerKnown(pharmacy, customer);
  }

  @Test
  void paymentHistoryFilters() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("receipt_number")).thenReturn("RCPT-1");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("payment_mode")).thenReturn("UPI");
              when(rs.getLong("amount_paise")).thenReturn(100L);
              when(rs.getString("notes")).thenReturn("n");
              when(rs.getLong("outstanding_after_paise")).thenReturn(0L);
              when(rs.getString("name")).thenReturn("A");
              when(rs.getString("phone")).thenReturn("+91");
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

    assertThat(
            store.paymentHistory(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "UPI", "A", 10, 0))
        .hasSize(1);
    assertThat(
            store.countPaymentHistory(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "UPI", "A"))
        .isEqualTo(1);
    assertThat(
            store.paymentHistoryTotalPaise(
                pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "UPI", "A"))
        .isEqualTo(1);
  }

  @Test
  void listOutstandingWithCustomersAndSorts() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              if (sql.contains("GROUP BY")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("customer_id")).thenReturn(customer);
                when(rs.getString("name")).thenReturn("A");
                when(rs.getString("phone")).thenReturn("+91");
                when(rs.getLong("outstanding")).thenReturn(7000L);
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(customer)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("reference_number")).thenReturn("INV-1");
              when(rs.getLong("amount_paise")).thenReturn(10_000L);
              when(rs.getTimestamp("invoice_at"))
                  .thenReturn(Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(5))));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), eq(customer)))
        .thenReturn(3_000L);

    assertThat(store.listOutstanding(pharmacy, false, "outstanding_asc", "A", 10, 0)).hasSize(1);
    assertThat(store.listOutstanding(pharmacy, false, "oldest_bill", null, 10, 0)).hasSize(1);
    assertThat(store.listOutstanding(pharmacy, true, "outstanding_desc", " ", 10, 0)).isEmpty();
    assertThat(store.countOutstanding(pharmacy, false, "A")).isEqualTo(1);
  }

  @Test
  void agingBucketsAllRangesAndNullSeq() throws Exception {
    UUID c2 = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenReturn(List.of(customer, c2));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), any()))
        .thenAnswer(
            inv -> {
              UUID cust = inv.getArgument(3);
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("reference_number")).thenReturn("INV");
              when(rs.getLong("amount_paise")).thenReturn(1000L);
              Instant at =
                  cust.equals(customer)
                      ? Instant.parse("2026-07-20T00:00:00Z")
                      : Instant.parse("2026-05-01T00:00:00Z");
              when(rs.getTimestamp("invoice_at")).thenReturn(Timestamp.from(at));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), any())).thenReturn(0L);

    KhataStore.AgingBuckets buckets = store.aging(pharmacy, LocalDate.of(2026, 7, 24));
    assertThat(buckets.current0To30Paise()).isEqualTo(1000L);
    assertThat(buckets.overdue60PlusPaise()).isEqualTo(1000L);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy))).thenReturn(List.of(customer));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(customer)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("reference_number")).thenReturn("INV");
              when(rs.getLong("amount_paise")).thenReturn(500L);
              when(rs.getTimestamp("invoice_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-06-10T00:00:00Z")));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.aging(pharmacy, LocalDate.of(2026, 7, 24)).overdue31To60Paise())
        .isEqualTo(500L);

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(100L);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(customer))).thenReturn(List.of());
    String rcpt =
        store
            .recordRepayment(pharmacy, customer, 50L, "CASH", null, null, UUID.randomUUID(), now)
            .receiptNumber();
    assertThat(rcpt).startsWith("RCPT-");
  }

  @Test
  void postCreditSaleFallbackInvoiceNumberAndNullOutstanding() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    store.postCreditSale(customer, UUID.randomUUID(), 100L, pharmacy);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(-5L);
    assertThat(store.outstandingPaise(pharmacy, customer)).isZero();
  }

  @Test
  void reminderLogAndLastReminder() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of(now));
    assertThat(store.lastReminderAt(pharmacy, customer)).contains(now);
    store.insertReminderLog(UUID.randomUUID(), pharmacy, customer, "SMS", "POLITE", "msg", now);
  }

  @Test
  void unpaidFullyCoveredSkipped() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(customer)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("reference_number")).thenReturn("INV");
              when(rs.getLong("amount_paise")).thenReturn(1000L);
              when(rs.getTimestamp("invoice_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), eq(customer)))
        .thenReturn(1000L);
    assertThat(store.unpaidBills(pharmacy, customer, LocalDate.of(2026, 7, 24))).isEmpty();
  }

  @Test
  void kpiUsesAging() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(10L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(100L);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy))).thenReturn(List.of());
    KhataStore.KpiSnapshot kpi =
        store.kpi(pharmacy, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
    assertThat(kpi.collectedThisMonthPaise()).isEqualTo(10L);
    assertThat(kpi.allTimeCreditGivenPaise()).isEqualTo(100L);
  }

  @Test
  void mapperLambdasAndOverdueFilter() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              ResultSet rs = mock(ResultSet.class);
              if (sql.contains("credit_limit_paise")) {
                when(rs.getLong(1)).thenReturn(2_000_000L);
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("invoice_number")) {
                when(rs.getString(1)).thenReturn("INV-X");
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("khata_reminder_log")) {
                when(rs.getTimestamp(1)).thenReturn(Timestamp.from(now));
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              ResultSet rs = mock(ResultSet.class);
              if (sql.contains("FROM customers")) {
                when(rs.getObject("id")).thenReturn(customer);
                when(rs.getString("name")).thenReturn("A");
                when(rs.getString("phone")).thenReturn("+91");
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });
    assertThat(store.creditLimitPaise(pharmacy, customer)).isEqualTo(2_000_000L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
    store.postCreditSale(customer, UUID.randomUUID(), 100L, pharmacy);
    assertThat(store.findCustomer(customer)).isPresent();
    assertThat(store.lastReminderAt(pharmacy, customer)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject(1)).thenReturn(customer);
              return List.of(mapper.mapRow(rs, 0));
            });

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              if (sql.contains("GROUP BY")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("customer_id")).thenReturn(customer);
                when(rs.getString("name")).thenReturn("A");
                when(rs.getString("phone")).thenReturn("+91");
                when(rs.getLong("outstanding")).thenReturn(5000L);
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(customer)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs1 = mock(ResultSet.class);
              when(rs1.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs1.getString("reference_number")).thenReturn("INV-OLD");
              when(rs1.getLong("amount_paise")).thenReturn(3000L);
              when(rs1.getTimestamp("invoice_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-04-01T00:00:00Z")));
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs2.getString("reference_number")).thenReturn("INV-NEW");
              when(rs2.getLong("amount_paise")).thenReturn(2000L);
              when(rs2.getTimestamp("invoice_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
              ResultSet rs3 = mock(ResultSet.class);
              when(rs3.getObject("invoice_id")).thenReturn(UUID.randomUUID());
              when(rs3.getString("reference_number")).thenReturn("INV-MID");
              when(rs3.getLong("amount_paise")).thenReturn(1000L);
              when(rs3.getTimestamp("invoice_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-03-01T00:00:00Z")));
              return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 0), mapper.mapRow(rs3, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy), eq(customer)))
        .thenReturn(0L);

    assertThat(store.listOutstanding(pharmacy, true, null, null, 10, 0)).hasSize(1);
    assertThat(store.countOutstanding(pharmacy, true, null)).isEqualTo(1);
    store.paymentHistory(pharmacy, null, null, "  ", "  ", 10, 0);

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(5);
    String receipt =
        store.recordCreditRepayment(customer, null, 100L, pharmacy, "CASH", null, null, null);
    assertThat(receipt).startsWith("RCPT-");
  }
}
