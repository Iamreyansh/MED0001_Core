package com.nammamedmate.integration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.domain.EinvoiceApiCallLog;
import com.nammamedmate.integration.domain.EinvoiceApiTypes;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import com.nammamedmate.integration.domain.EinvoiceStatuses;
import com.nammamedmate.integration.domain.FinancialYears;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.domain.GovernmentApiCallLog;
import com.nammamedmate.integration.domain.GovernmentApiTypes;
import com.nammamedmate.integration.domain.GovernmentVerificationCacheEntry;
import com.nammamedmate.integration.domain.GovernmentVerificationTypes;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import com.nammamedmate.integration.domain.MapsApiTypes;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcStoresTest {

  @Test
  @SuppressWarnings("unchecked")
  void paymentStoreCrud() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRazorpayPaymentRecordStore store = new JdbcRazorpayPaymentRecordStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    RazorpayPaymentRecord record =
        new RazorpayPaymentRecord(
            id, UUID.randomUUID(), "order_1", "pay_1", 100, "INR", "upi", "captured", now, now);
    store.insert(record);
    store.update(record);
    RazorpayPaymentRecord open =
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "order_2",
            null,
            50,
            "INR",
            null,
            "created",
            now,
            null);
    store.insert(open);
    store.update(open);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<RazorpayPaymentRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("platform_order_id")).thenReturn(record.platformOrderId());
              when(rs.getString("razorpay_order_id")).thenReturn("order_1");
              when(rs.getString("razorpay_payment_id")).thenReturn("pay_1");
              when(rs.getInt("amount_paise")).thenReturn(100);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("payment_method")).thenReturn("upi");
              when(rs.getString("status")).thenReturn("captured");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("order_1"))).thenReturn(List.of());
    assertThat(store.findByRazorpayOrderId("order_1")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("pay_1"))).thenReturn(List.of());
    assertThat(store.findByRazorpayPaymentId("pay_1")).isEmpty();
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void fundAccountStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRazorpayXFundAccountStore store = new JdbcRazorpayXFundAccountStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            id,
            "PHARMACY",
            UUID.randomUUID(),
            "cont",
            "fa_1",
            "HDFC",
            "6789",
            "HDFC0001234",
            "N",
            true,
            now);
    store.insert(fa);
    store.deactivate(id);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<RazorpayXFundAccount> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(fa.entityId());
              when(rs.getString("razorpayx_contact_id")).thenReturn("cont");
              when(rs.getString("fund_account_id")).thenReturn("fa_1");
              when(rs.getString("bank_name")).thenReturn("HDFC");
              when(rs.getString("account_last4")).thenReturn("6789");
              when(rs.getString("ifsc")).thenReturn("HDFC0001234");
              when(rs.getString("account_holder_name")).thenReturn("N");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findActiveByEntity("PHARMACY", fa.entityId())).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("fa_1")))
        .thenAnswer(
            inv -> {
              RowMapper<RazorpayXFundAccount> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(fa.entityId());
              when(rs.getString("razorpayx_contact_id")).thenReturn("cont");
              when(rs.getString("fund_account_id")).thenReturn("fa_1");
              when(rs.getString("bank_name")).thenReturn("HDFC");
              when(rs.getString("account_last4")).thenReturn("6789");
              when(rs.getString("ifsc")).thenReturn("HDFC0001234");
              when(rs.getString("account_holder_name")).thenReturn("N");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByFundAccountId("fa_1")).isPresent();
  }

  @Test
  @SuppressWarnings("unchecked")
  void payoutStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRazorpayXPayoutRecordStore store = new JdbcRazorpayXPayoutRecordStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    RazorpayXPayoutRecord record =
        new RazorpayXPayoutRecord(
            id,
            "PHARMACY",
            UUID.randomUUID(),
            "fa_1",
            "pout_1",
            "ref",
            1000L,
            "IMPS",
            "failed",
            0,
            now,
            now,
            "err");
    store.insert(record);
    store.update(record);
    RazorpayXPayoutRecord open =
        new RazorpayXPayoutRecord(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "fa_1",
            "pout_2",
            "ref2",
            1000L,
            "IMPS",
            "processing",
            0,
            now,
            null,
            null);
    store.insert(open);
    store.update(open);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.findById(id)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("pout_1"))).thenReturn(List.of());
    assertThat(store.findByRazorpayxPayoutId("pout_1")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("ref"))).thenReturn(List.of());
    assertThat(store.findByReferenceId("ref")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(10)))
        .thenAnswer(
            inv -> {
              RowMapper<RazorpayXPayoutRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(record.entityId());
              when(rs.getString("fund_account_id")).thenReturn("fa_1");
              when(rs.getString("razorpayx_payout_id")).thenReturn("pout_1");
              when(rs.getString("reference_id")).thenReturn("ref");
              when(rs.getLong("amount_paise")).thenReturn(1000L);
              when(rs.getString("mode")).thenReturn("IMPS");
              when(rs.getString("status")).thenReturn("failed");
              when(rs.getInt("retry_count")).thenReturn(0);
              when(rs.getTimestamp("initiated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("processed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn("err");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findRetryEligible(now, 10)).hasSize(1);
    ArgumentCaptor<Object[]> unused = ArgumentCaptor.forClass(Object[].class);
    assertThat(unused).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsCallLogAndGeocodeCache() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcMapsApiCallLogStore logStore = new JdbcMapsApiCallLogStore(jdbc);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    logStore.insert(
        new MapsApiCallLog(
            UUID.randomUUID(),
            MapsApiTypes.GEOCODE,
            "fwd",
            "OK",
            12,
            true,
            new BigDecimal("0.4200"),
            now,
            "order"));
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Timestamp.class)))
        .thenReturn(new BigDecimal("1.2500"));
    assertThat(logStore.sumEstimatedCostSince(now)).isEqualByComparingTo("1.2500");
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Timestamp.class)))
        .thenReturn(null);
    assertThat(logStore.sumEstimatedCostSince(now)).isEqualByComparingTo(BigDecimal.ZERO);

    JdbcGeocodeCacheStore cacheStore = new JdbcGeocodeCacheStore(jdbc);
    GeocodeCacheEntry entry =
        new GeocodeCacheEntry("k", 12.97, 77.64, "addr", "pid", now, now.plusSeconds(3600));
    cacheStore.upsert(entry);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("k"), any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<GeocodeCacheEntry> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("cache_key")).thenReturn("k");
              when(rs.getBigDecimal("lat")).thenReturn(BigDecimal.valueOf(12.97));
              when(rs.getBigDecimal("lng")).thenReturn(BigDecimal.valueOf(77.64));
              when(rs.getString("formatted_address")).thenReturn("addr");
              when(rs.getString("place_id")).thenReturn("pid");
              when(rs.getTimestamp("cached_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(cacheStore.findValid("k", now)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("missing"), any(Timestamp.class)))
        .thenReturn(List.of());
    assertThat(cacheStore.findValid("missing", now)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void governmentCacheAndCallLog() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcGovernmentVerificationCacheStore cache =
        new JdbcGovernmentVerificationCacheStore(jdbc, mapper);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    GovernmentVerificationCacheEntry entry =
        new GovernmentVerificationCacheEntry(
            id,
            GovernmentVerificationTypes.GSTIN,
            "29ABCDE1234F1ZW",
            null,
            Map.of("valid", true),
            true,
            null,
            now,
            now.plusSeconds(3600));
    cache.upsert(entry);
    cache.upsert(
        new GovernmentVerificationCacheEntry(
            UUID.randomUUID(),
            GovernmentVerificationTypes.DRUG_LICENCE,
            "KA/1",
            "Karnataka",
            Map.of("status", "ACTIVE"),
            true,
            LocalDate.of(2030, 1, 1),
            now,
            now.plusSeconds(3600)));

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(GovernmentVerificationTypes.GSTIN),
            eq("29ABCDE1234F1ZW"),
            eq(""),
            any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<GovernmentVerificationCacheEntry> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("verification_type")).thenReturn("GSTIN");
              when(rs.getString("identifier")).thenReturn("29ABCDE1234F1ZW");
              when(rs.getString("state")).thenReturn("");
              when(rs.getString("result_json")).thenReturn("{\"valid\":true,\"gstin\":\"x\"}");
              when(rs.getBoolean("is_valid")).thenReturn(true);
              when(rs.getDate("expiry_date")).thenReturn(null);
              when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(cache.findValid(GovernmentVerificationTypes.GSTIN, "29ABCDE1234F1ZW", null, now))
        .isPresent()
        .get()
        .extracting(e -> e.resultJson().get("valid"))
        .isEqualTo(true);

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(GovernmentVerificationTypes.GSTIN),
            eq("missing"),
            eq(""),
            any(Timestamp.class)))
        .thenReturn(List.of());
    assertThat(cache.findValid(GovernmentVerificationTypes.GSTIN, "missing", null, now)).isEmpty();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(GovernmentVerificationTypes.GSTIN),
            eq("nulljson"),
            eq(""),
            any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<GovernmentVerificationCacheEntry> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("verification_type")).thenReturn("GSTIN");
              when(rs.getString("identifier")).thenReturn("nulljson");
              when(rs.getString("state")).thenReturn("");
              when(rs.getString("result_json")).thenReturn(null);
              when(rs.getBoolean("is_valid")).thenReturn(false);
              when(rs.getDate("expiry_date")).thenReturn(null);
              when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(cache.findValid(GovernmentVerificationTypes.GSTIN, "nulljson", null, now))
        .isPresent();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(GovernmentVerificationTypes.DRUG_LICENCE),
            eq("KA/1"),
            eq("Karnataka"),
            any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<GovernmentVerificationCacheEntry> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("verification_type")).thenReturn("DRUG_LICENCE");
              when(rs.getString("identifier")).thenReturn("KA/1");
              when(rs.getString("state")).thenReturn("Karnataka");
              when(rs.getString("result_json")).thenReturn("   ");
              when(rs.getBoolean("is_valid")).thenReturn(true);
              when(rs.getDate("expiry_date")).thenReturn(java.sql.Date.valueOf("2030-01-01"));
              when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(cache.findValid(GovernmentVerificationTypes.DRUG_LICENCE, "KA/1", "Karnataka", now))
        .isPresent();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(GovernmentVerificationTypes.FSSAI),
            eq("bad"),
            eq(""),
            any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<GovernmentVerificationCacheEntry> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("verification_type")).thenReturn("FSSAI");
              when(rs.getString("identifier")).thenReturn("bad");
              when(rs.getString("state")).thenReturn(null);
              when(rs.getString("result_json")).thenReturn("{not-json");
              when(rs.getBoolean("is_valid")).thenReturn(false);
              when(rs.getDate("expiry_date")).thenReturn(null);
              when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> cache.findValid(GovernmentVerificationTypes.FSSAI, "bad", null, now))
        .isInstanceOf(IllegalStateException.class);

    JdbcGovernmentApiCallLogStore logs = new JdbcGovernmentApiCallLogStore(jdbc);
    logs.insert(
        new GovernmentApiCallLog(
            UUID.randomUUID(),
            GovernmentApiTypes.GSTN,
            "29ABCDE1234F1ZW",
            200,
            "OK",
            5,
            false,
            "PHARMACY",
            UUID.randomUUID(),
            now));
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void einvoiceStoresCrud() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcEinvoiceIrnRecordStore store = new JdbcEinvoiceIrnRecordStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    LocalDate invDate = LocalDate.of(2026, 7, 24);
    EinvoiceIrnRecord record =
        new EinvoiceIrnRecord(
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64),
            "232410141234567",
            now,
            "29ABCDE1234F1ZW",
            "27AAPFU0939F1ZV",
            "INV-1",
            invDate,
            "INV",
            FinancialYears.of(invDate),
            new BigDecimal("940.80"),
            "data:image/png;base64,xx",
            "{\"Signature\":\"x\"}",
            EinvoiceStatuses.ACTIVE,
            null,
            null,
            now,
            null);
    EinvoiceIrnRecord withCancelTs =
        new EinvoiceIrnRecord(
            id,
            record.pharmacyId(),
            record.platformInvoiceId(),
            record.irn(),
            record.ackNumber(),
            record.ackDate(),
            record.sellerGstin(),
            record.buyerGstin(),
            record.invoiceNumber(),
            record.invoiceDate(),
            record.documentType(),
            record.financialYear(),
            record.totalInvoiceValue(),
            record.qrCodeUrl(),
            record.signedInvoiceJson(),
            EinvoiceStatuses.CANCELLED,
            "1",
            "dup",
            now,
            now);
    store.insert(record); // null cancelled_at on insert
    store.insert(withCancelTs); // non-null cancelled_at on insert
    store.update(record); // null cancelled_at on update
    store.update(withCancelTs); // non-null cancelled_at on update

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.irn())))
        .thenAnswer(
            inv -> {
              RowMapper<EinvoiceIrnRecord> mapper = inv.getArgument(1);
              ResultSet rs = mockResult(record, now);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByIrn(record.irn())).isPresent();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(record.sellerGstin()),
            eq(record.buyerGstin()),
            eq("INV"),
            eq(record.financialYear()),
            eq("INV-1")))
        .thenAnswer(
            inv -> {
              RowMapper<EinvoiceIrnRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResult(withCancelTs, now), 0));
            });
    assertThat(
            store.findByDocumentKey(
                record.sellerGstin(), record.buyerGstin(), "INV", record.financialYear(), "INV-1"))
        .isPresent()
        .get()
        .extracting(EinvoiceIrnRecord::cancelledAt)
        .isNotNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<EinvoiceIrnRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockResult(record, now), 0));
            });
    assertThat(store.findById(id)).isPresent();

    JdbcEinvoiceApiCallLogStore logs = new JdbcEinvoiceApiCallLogStore(jdbc);
    logs.insert(
        new EinvoiceApiCallLog(
            UUID.randomUUID(), EinvoiceApiTypes.GENERATE_IRN, "seller=x", 200, "OK", 3, now));
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), any(), any(), any(), any(), any(), any(), any());

    JdbcPharmacyEinvoiceFlagStore flags = new JdbcPharmacyEinvoiceFlagStore(jdbc);
    assertThat(flags.findEInvoicingEnabled(null)).isEmpty();
    UUID pharmacyId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId)))
        .thenAnswer(
            inv -> {
              RowMapper<Boolean> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBoolean("e_invoicing_enabled")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(flags.findEInvoicingEnabled(pharmacyId)).contains(true);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId))).thenReturn(List.of());
    assertThat(flags.findEInvoicingEnabled(pharmacyId)).isEmpty();
  }

  private static ResultSet mockResult(EinvoiceIrnRecord record, Instant now) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(record.id());
    when(rs.getObject("pharmacy_id")).thenReturn(record.pharmacyId());
    when(rs.getObject("platform_invoice_id")).thenReturn(record.platformInvoiceId());
    when(rs.getString("irn")).thenReturn(record.irn());
    when(rs.getString("ack_number")).thenReturn(record.ackNumber());
    when(rs.getTimestamp("ack_date")).thenReturn(Timestamp.from(record.ackDate()));
    when(rs.getString("seller_gstin")).thenReturn(record.sellerGstin());
    when(rs.getString("buyer_gstin")).thenReturn(record.buyerGstin());
    when(rs.getString("invoice_number")).thenReturn(record.invoiceNumber());
    when(rs.getDate("invoice_date")).thenReturn(java.sql.Date.valueOf(record.invoiceDate()));
    when(rs.getString("document_type")).thenReturn(record.documentType());
    when(rs.getString("financial_year")).thenReturn(record.financialYear());
    when(rs.getBigDecimal("total_invoice_value")).thenReturn(record.totalInvoiceValue());
    when(rs.getString("qr_code_url")).thenReturn(record.qrCodeUrl());
    when(rs.getString("signed_invoice_json")).thenReturn(record.signedInvoiceJson());
    when(rs.getString("status")).thenReturn(record.status());
    when(rs.getString("cancel_reason_code")).thenReturn(record.cancelReasonCode());
    when(rs.getString("cancel_remark")).thenReturn(record.cancelRemark());
    when(rs.getTimestamp("generated_at")).thenReturn(Timestamp.from(record.generatedAt()));
    when(rs.getTimestamp("cancelled_at"))
        .thenReturn(record.cancelledAt() == null ? null : Timestamp.from(record.cancelledAt()));
    return rs;
  }
}
