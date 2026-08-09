package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.domain.InvoiceStatus;
import com.nammamedmate.crm.domain.SaasGst;
import com.nammamedmate.crm.domain.SaasInvoice;
import com.nammamedmate.crm.domain.SaasInvoiceLineItem;
import java.math.BigDecimal;
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
class JdbcSaasInvoiceStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void coversPersistencePaths() throws Exception {
    JdbcSaasInvoiceStore store = new JdbcSaasInvoiceStore(jdbc);
    UUID id = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    LocalDate today = LocalDate.of(2026, 7, 24);
    stubRs(id, accountId, subId, now, today);

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> map(inv.getArgument(1)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> map(inv.getArgument(1)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> map(inv.getArgument(1)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> map(inv.getArgument(1)));
    lenient()
        .when(
            jdbc.query(
                anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> map(inv.getArgument(1)));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("GROUP BY")) {
                when(rs.getString("plan_name")).thenReturn("STARTER");
                when(rs.getLong("collected")).thenReturn(500L);
              }
              if (sql.contains("paid_at, due_at")) {
                when(rs.getTimestamp("paid_at")).thenReturn(Timestamp.from(now));
                when(rs.getDate("due_at")).thenReturn(Date.valueOf(today.minusDays(3)));
              }
              return map(inv.getArgument(1));
            });
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(4L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(4L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(4L);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any()))
        .thenReturn(4L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(3);

    SaasInvoice invoice =
        new SaasInvoice(
            id,
            "NMM-INV-2026-07-000001",
            accountId,
            subId,
            "STARTER",
            today,
            today.plusMonths(1),
            69900,
            SaasGst.RATE_PCT,
            SaasGst.gstPaise(69900),
            SaasGst.totalWithGstPaise(69900),
            InvoiceStatus.DUE,
            today,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    store.insert(
        invoice,
        List.of(
            new SaasInvoiceLineItem(
                UUID.randomUUID(), id, "STARTER Plan - Monthly", "9983", 69900, "PLAN", now)));
    store.update(invoice);

    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByMarkPaidIdempotencyKey("k")).isPresent();
    assertThat(store.findByPayIdempotencyKey("p")).isPresent();
    assertThat(store.listLineItems(id)).isNotEmpty();
    store.listAdmin(new SaasInvoiceStore.AdminListFilter(null, null, null, null, null, 0, 10));
    store.listAdmin(
        new SaasInvoiceStore.AdminListFilter("DUE", "STARTER", accountId, today, today, 0, 5));
    store.countAdmin(new SaasInvoiceStore.AdminListFilter(null, null, null, null, null, 0, 10));
    store.countAdmin(
        new SaasInvoiceStore.AdminListFilter("DUE", "STARTER", accountId, today, today, 0, 5));
    store.chips(null, null);
    store.chips(today, today);
    store.collectedByPlan(null, null);
    store.collectedByPlan(today, today);
    store.listForAccount(accountId, 0, 10);
    store.countForAccount(accountId);
    assertThat(store.listOpenStatuses(accountId)).isNotEmpty();
    assertThat(store.pharmacyProfile(accountId).pharmacyName()).isEqualTo("Apollo");
    assertThat(store.nextInvoiceSeq("2026-07")).isEqualTo(3);
    store.findOpenPastDue(today);
    store.findForDunning(today);

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.pharmacyProfile(accountId).pharmacyName()).isEqualTo("Pharmacy");
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.nextInvoiceSeq("2026-08")).isEqualTo(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(store.countForAccount(accountId)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(
            store.countAdmin(
                new SaasInvoiceStore.AdminListFilter(null, null, null, null, null, 0, 10)))
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    assertThat(store.chips(null, null).collectionRatePct()).isEqualByComparingTo("0.0");

    assertThat(JdbcSaasInvoiceStore.formatAddress(null)).isEmpty();
    assertThat(JdbcSaasInvoiceStore.formatAddress("   ")).isEmpty();
    assertThat(JdbcSaasInvoiceStore.formatAddress("{}")).isEmpty();
    assertThat(JdbcSaasInvoiceStore.formatAddress("{\"x\":1}")).contains("x");
    assertThat(JdbcSaasInvoiceStore.formatAddress("raw")).isEqualTo("raw");
    store.listAdmin(new SaasInvoiceStore.AdminListFilter(" ", " ", null, null, null, 0, 10));
    verify(jdbc, atLeastOnce())
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
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private List<?> map(RowMapper mapper) throws Exception {
    // DSO chip mapper requires non-null paid_at
    when(rs.getTimestamp("paid_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T10:00:00Z")));
    when(rs.getDate("due_at")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 21)));
    return List.of(mapper.mapRow(rs, 0));
  }

  private void stubRs(UUID id, UUID accountId, UUID subId, Instant now, LocalDate today)
      throws Exception {
    lenient().when(rs.getObject("id")).thenReturn(id);
    lenient().when(rs.getString("invoice_number")).thenReturn("NMM-INV-2026-07-000001");
    lenient().when(rs.getObject("account_id")).thenReturn(accountId);
    lenient().when(rs.getObject("subscription_id")).thenReturn(subId);
    lenient().when(rs.getString("plan_name")).thenReturn("STARTER");
    lenient().when(rs.getDate("billing_period_from")).thenReturn(Date.valueOf(today));
    lenient().when(rs.getDate("billing_period_to")).thenReturn(Date.valueOf(today.plusMonths(1)));
    lenient().when(rs.getLong("subtotal_paise")).thenReturn(69900L);
    lenient().when(rs.getBigDecimal("gst_rate_pct")).thenReturn(new BigDecimal("18.00"));
    lenient().when(rs.getLong("gst_amount_paise")).thenReturn(12582L);
    lenient().when(rs.getLong("total_amount_paise")).thenReturn(82482L);
    lenient().when(rs.getString("status")).thenReturn("DUE");
    lenient().when(rs.getDate("due_at")).thenReturn(Date.valueOf(today));
    lenient().when(rs.getTimestamp("paid_at")).thenReturn(null);
    lenient().when(rs.getString("payment_mode")).thenReturn(null);
    lenient().when(rs.getString("reference_number")).thenReturn(null);
    lenient().when(rs.getObject("marked_paid_by")).thenReturn(null);
    lenient().when(rs.getInt("dunning_step")).thenReturn(0);
    lenient().when(rs.getString("waive_reason")).thenReturn(null);
    lenient().when(rs.getString("pdf_object_key")).thenReturn(null);
    lenient().when(rs.getString("checkout_url")).thenReturn(null);
    lenient().when(rs.getTimestamp("checkout_expires_at")).thenReturn(null);
    lenient().when(rs.getString("mark_paid_idempotency_key")).thenReturn(null);
    lenient().when(rs.getString("pay_idempotency_key")).thenReturn(null);
    lenient().when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    lenient().when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    lenient().when(rs.getObject("invoice_id")).thenReturn(id);
    lenient().when(rs.getString("description")).thenReturn("line");
    lenient().when(rs.getString("sac_code")).thenReturn("9983");
    lenient().when(rs.getLong("amount_paise")).thenReturn(69900L);
    lenient().when(rs.getString("item_type")).thenReturn("PLAN");
    lenient().when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
    lenient().when(rs.getString("pharmacy_name")).thenReturn("Apollo");
    lenient().when(rs.getString("gstin")).thenReturn("29X");
    lenient().when(rs.getString("address_json")).thenReturn("{\"a\":1}");
  }
}
