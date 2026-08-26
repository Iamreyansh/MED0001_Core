package com.nammamedmate.integration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import com.nammamedmate.integration.domain.MapsApiTypes;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
    JdbcCashfreePaymentRecordStore store = new JdbcCashfreePaymentRecordStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    CashfreePaymentRecord record =
        new CashfreePaymentRecord(
            id, UUID.randomUUID(), "order_1", "pay_1", 100, "INR", "upi", "captured", now, now);
    store.insert(record);
    store.update(record);
    CashfreePaymentRecord open =
        new CashfreePaymentRecord(
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
              RowMapper<CashfreePaymentRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("platform_order_id")).thenReturn(record.platformOrderId());
              when(rs.getString("cashfree_order_id")).thenReturn("order_1");
              when(rs.getString("cashfree_payment_id")).thenReturn("pay_1");
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
    assertThat(store.findByGatewayOrderId("order_1")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("pay_1"))).thenReturn(List.of());
    assertThat(store.findByGatewayPaymentId("pay_1")).isEmpty();
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void beneficiaryStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCashfreeBeneficiaryStore store = new JdbcCashfreeBeneficiaryStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    CashfreeBeneficiary fa =
        new CashfreeBeneficiary(
            id,
            "PHARMACY",
            UUID.randomUUID(),
            "cont",
            "bene_1",
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
              RowMapper<CashfreeBeneficiary> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(fa.entityId());
              when(rs.getString("cashfree_contact_id")).thenReturn("cont");
              when(rs.getString("beneficiary_id")).thenReturn("bene_1");
              when(rs.getString("bank_name")).thenReturn("HDFC");
              when(rs.getString("account_last4")).thenReturn("6789");
              when(rs.getString("ifsc")).thenReturn("HDFC0001234");
              when(rs.getString("account_holder_name")).thenReturn("N");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findActiveByEntity("PHARMACY", fa.entityId())).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("bene_1")))
        .thenAnswer(
            inv -> {
              RowMapper<CashfreeBeneficiary> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(fa.entityId());
              when(rs.getString("cashfree_contact_id")).thenReturn("cont");
              when(rs.getString("beneficiary_id")).thenReturn("bene_1");
              when(rs.getString("bank_name")).thenReturn("HDFC");
              when(rs.getString("account_last4")).thenReturn("6789");
              when(rs.getString("ifsc")).thenReturn("HDFC0001234");
              when(rs.getString("account_holder_name")).thenReturn("N");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByBeneficiaryId("bene_1")).isPresent();
  }

  @Test
  @SuppressWarnings("unchecked")
  void payoutStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCashfreePayoutRecordStore store = new JdbcCashfreePayoutRecordStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    CashfreePayoutRecord record =
        new CashfreePayoutRecord(
            id,
            "PHARMACY",
            UUID.randomUUID(),
            "bene_1",
            "xfer_1",
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
    CashfreePayoutRecord open =
        new CashfreePayoutRecord(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "bene_1",
            "xfer_2",
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
    when(jdbc.query(anyString(), any(RowMapper.class), eq("xfer_1"))).thenReturn(List.of());
    assertThat(store.findByCashfreexPayoutId("xfer_1")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("ref"))).thenReturn(List.of());
    assertThat(store.findByReferenceId("ref")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(10)))
        .thenAnswer(
            inv -> {
              RowMapper<CashfreePayoutRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("entity_type")).thenReturn("PHARMACY");
              when(rs.getObject("entity_id")).thenReturn(record.entityId());
              when(rs.getString("beneficiary_id")).thenReturn("bene_1");
              when(rs.getString("cashfree_transfer_id")).thenReturn("xfer_1");
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
}
