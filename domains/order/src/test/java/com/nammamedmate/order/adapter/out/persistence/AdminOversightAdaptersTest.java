package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.adapter.out.cache.RedisLiveFeedCache;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminListFilter;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminOrderListRow;
import com.nammamedmate.order.domain.AdminOrderExportJob;
import com.nammamedmate.order.domain.AdminOrderSegment;
import com.nammamedmate.order.domain.ExportJobStatus;
import com.nammamedmate.order.domain.LiableParty;
import com.nammamedmate.order.domain.OrderDispute;
import com.nammamedmate.order.domain.OrderNote;
import com.nammamedmate.order.domain.PaymentMethod;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class AdminOversightAdaptersTest {

  @TempDir Path temp;

  @Test
  @SuppressWarnings("unchecked")
  void disputeNoteExportRowMappers() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcOrderDisputeStore disputes = new JdbcOrderDisputeStore(jdbc);
    JdbcOrderNoteStore notes = new JdbcOrderNoteStore(jdbc);
    JdbcAdminOrderExportStore exports = new JdbcAdminOrderExportStore(jdbc);

    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T06:00:00Z");
    OrderDispute d =
        new OrderDispute(
            id,
            UUID.randomUUID(),
            "reason",
            LiableParty.RIDER,
            UUID.randomUUID(),
            now,
            false,
            null,
            null);
    disputes.insert(d);

    ArgumentCaptor<RowMapper<OrderDispute>> disputeMapper =
        ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(anyString(), disputeMapper.capture(), any())).thenReturn(List.of(d));
    assertThat(disputes.findOpenByOrderId(d.orderId())).contains(d);
    when(jdbc.query(anyString(), disputeMapper.capture(), any())).thenReturn(List.of());
    assertThat(disputes.findOpenByOrderId(d.orderId())).isEmpty();
    ResultSet drs = mockOrderDisputeRs(d, now);
    assertThat(disputeMapper.getValue().mapRow(drs, 0).liableParty()).isEqualTo(LiableParty.RIDER);

    // resolved_at present branch + insert with resolvedAt
    when(drs.getTimestamp("resolved_at")).thenReturn(Timestamp.from(now));
    assertThat(disputeMapper.getValue().mapRow(drs, 0).resolvedAt()).isEqualTo(now);
    OrderDispute resolved =
        new OrderDispute(
            UUID.randomUUID(),
            d.orderId(),
            "r",
            LiableParty.PLATFORM,
            UUID.randomUUID(),
            now,
            true,
            now,
            "done");
    disputes.insert(resolved);

    OrderNote n =
        new OrderNote(UUID.randomUUID(), d.orderId(), "note", true, UUID.randomUUID(), now);
    notes.insert(n);
    ArgumentCaptor<RowMapper<OrderNote>> noteMapper = ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(anyString(), noteMapper.capture(), eq(d.orderId()))).thenReturn(List.of());
    notes.listByOrderId(d.orderId());
    ResultSet nrs = mock(ResultSet.class);
    when(nrs.getObject("id")).thenReturn(n.id());
    when(nrs.getObject("order_id")).thenReturn(n.orderId());
    when(nrs.getString("note")).thenReturn(n.note());
    when(nrs.getBoolean("is_pinned")).thenReturn(true);
    when(nrs.getObject("added_by")).thenReturn(n.addedBy());
    when(nrs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    assertThat(noteMapper.getValue().mapRow(nrs, 0).pinned()).isTrue();

    AdminOrderExportJob job =
        new AdminOrderExportJob(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{}",
            3,
            ExportJobStatus.READY,
            "exports/x.csv",
            now,
            now);
    exports.insert(job);
    AdminOrderExportJob pendingJob =
        new AdminOrderExportJob(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{}",
            null,
            ExportJobStatus.PROCESSING,
            null,
            now,
            null);
    exports.insert(pendingJob);
    ArgumentCaptor<RowMapper<AdminOrderExportJob>> exportMapper =
        ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(anyString(), exportMapper.capture(), any())).thenReturn(List.of(job));
    assertThat(exports.findById(job.id())).contains(job);
    when(jdbc.query(anyString(), exportMapper.capture(), any())).thenReturn(List.of());
    assertThat(exports.findById(job.id())).isEmpty();
    ResultSet ers = mock(ResultSet.class);
    when(ers.getObject("id")).thenReturn(job.id());
    when(ers.getObject("requested_by")).thenReturn(job.requestedBy());
    when(ers.getString("filters")).thenReturn("{}");
    when(ers.getObject("row_count")).thenReturn(3);
    when(ers.getString("status")).thenReturn("READY");
    when(ers.getString("s3_key")).thenReturn("exports/x.csv");
    when(ers.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(ers.getTimestamp("completed_at")).thenReturn(Timestamp.from(now));
    assertThat(exportMapper.getValue().mapRow(ers, 0).status()).isEqualTo(ExportJobStatus.READY);
    when(ers.getTimestamp("completed_at")).thenReturn(null);
    when(ers.getObject("row_count")).thenReturn(null);
    assertThat(exportMapper.getValue().mapRow(ers, 0).completedAt()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("PROCESSING"), eq(10)))
        .thenReturn(List.of(job));
    assertThat(exports.findByStatus(ExportJobStatus.PROCESSING, 10)).hasSize(1);
    exports.markReady(job.id(), "exports/x.csv", 12, now);
    exports.markFailed(job.id(), now);
  }

  @Test
  @SuppressWarnings("unchecked")
  void queryAdapterCoversSegmentsLookupsAndMapping() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminOrderQueryAdapter adapter = new JdbcAdminOrderQueryAdapter(jdbc, new ObjectMapper());
    Instant now = Instant.parse("2026-08-08T06:00:00Z");

    ArgumentCaptor<RowMapper<AdminOrderListRow>> rowMapper =
        ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(anyString(), rowMapper.capture(), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(
            adapter.count(
                new AdminListFilter(
                    AdminOrderSegment.ALL,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    1,
                    20)))
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

    for (AdminOrderSegment segment : AdminOrderSegment.values()) {
      AdminListFilter filter =
          new AdminListFilter(
              segment,
              "ravi",
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              PaymentMethod.UPI,
              true,
              LocalDate.of(2026, 8, 1),
              LocalDate.of(2026, 8, 8),
              now,
              1,
              20);
      assertThat(adapter.list(filter)).isEmpty();
      assertThat(adapter.count(filter)).isZero();
      assertThat(adapter.listAllForExport(filter, 5)).isEmpty();
    }
    AdminListFilter nullSegment =
        new AdminListFilter(null, "  ", null, null, null, null, null, null, null, now, 1, 20);
    assertThat(adapter.list(nullSegment)).isEmpty();
    AdminListFilter blankSearch =
        new AdminListFilter(
            AdminOrderSegment.ALL, "", null, null, null, null, null, null, null, now, 1, 20);
    assertThat(adapter.list(blankSearch)).isEmpty();
    AdminListFilter rxFalse =
        new AdminListFilter(
            AdminOrderSegment.ALL, null, null, null, null, null, false, null, null, now, 1, 20);
    assertThat(adapter.list(rxFalse)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
    assertThat(adapter.count(rxFalse)).isEqualTo(3L);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(1L);
              return ex.extractData(rs);
            });
    assertThat(
            adapter
                .summary(
                    new AdminListFilter(
                        AdminOrderSegment.ALL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        now,
                        1,
                        20))
                .totalOrders())
        .isEqualTo(1);

    assertThat(adapter.liveFeed(now, 10)).isEmpty();

    ResultSet ors = mockOrderListRs(now);
    AdminOrderListRow mapped = rowMapper.getAllValues().getFirst().mapRow(ors, 0);
    assertThat(mapped.pharmacyName()).isEqualTo("Sai");
    assertThat(mapped.disputed()).isTrue();

    // blank items / bad json branches
    when(ors.getString("items")).thenReturn("");
    assertThat(rowMapper.getAllValues().getFirst().mapRow(ors, 0).order().items()).isEmpty();
    when(ors.getString("items")).thenReturn("not-json");
    assertThat(rowMapper.getAllValues().getFirst().mapRow(ors, 0).order().items()).isEmpty();
    when(ors.getString("items")).thenReturn(null);
    assertThat(rowMapper.getAllValues().getFirst().mapRow(ors, 0).order().items()).isEmpty();
    when(ors.getBigDecimal("commission_pct")).thenReturn(null);
    when(ors.getString("area")).thenReturn("  ");
    assertThat(rowMapper.getAllValues().getFirst().mapRow(ors, 0).area()).isNull();

    UUID phId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(phId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              ResultSet prs = mock(ResultSet.class);
              when(prs.getObject("id")).thenReturn(phId);
              when(prs.getString("name")).thenReturn("Ph");
              when(prs.getString("area")).thenReturn(null);
              when(prs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("10"));
              return List.of(m.mapRow(prs, 0));
            });
    assertThat(adapter.findPharmacy(phId)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(phId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              ResultSet prs = mock(ResultSet.class);
              when(prs.getObject("id")).thenReturn(phId);
              when(prs.getString("name")).thenReturn("Ph");
              when(prs.getString("area")).thenReturn("Koramangala");
              when(prs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("10"));
              return List.of(m.mapRow(prs, 0));
            });
    assertThat(adapter.findPharmacy(phId).orElseThrow().area()).isEqualTo("Koramangala");
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(adapter.findPharmacy(UUID.randomUUID())).isEmpty();
    assertThat(adapter.findCustomer(UUID.randomUUID())).isEmpty();
    assertThat(adapter.findAdminName(UUID.randomUUID())).isEmpty();
    assertThat(adapter.findAddressArea(UUID.randomUUID())).isEmpty();

    UUID custId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(custId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              ResultSet crs = mock(ResultSet.class);
              when(crs.getObject("id")).thenReturn(custId);
              when(crs.getString("name")).thenReturn("Cust");
              when(crs.getString("phone")).thenReturn("+91");
              when(crs.getInt("total_orders")).thenReturn(2);
              when(crs.getLong("total_ltv_paise")).thenReturn(100L);
              return List.of(m.mapRow(crs, 0));
            });
    assertThat(adapter.findCustomer(custId)).isPresent();

    UUID adminId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              ResultSet ars = mock(ResultSet.class);
              when(ars.getObject("id")).thenReturn(adminId);
              when(ars.getString("name")).thenReturn("Admin");
              return List.of(m.mapRow(ars, 0));
            });
    assertThat(adapter.findAdminName(adminId)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> m = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("area_locality")).thenReturn(" ");
              return List.of(m.mapRow(rs, 0));
            });
    assertThat(adapter.findAddressArea(UUID.randomUUID())).isEmpty();
  }

  @Test
  void localExportAndRedisCacheBranches() throws Exception {
    LocalExportObjectStore store = new LocalExportObjectStore(temp, "file://" + temp);
    store.put("exports/a.csv", "a,b\n".getBytes(), "text/csv");
    assertThat(store.createDownloadUrl("a.csv")).contains("exports");
    assertThat(store.createDownloadUrl("exports/a.csv")).contains("exports");

    Path file = temp.resolve("not-a-dir");
    Files.writeString(file, "x");
    LocalExportObjectStore bad = new LocalExportObjectStore(file.resolve("child"), "file://x");
    assertThatThrownBy(() -> bad.put("k", new byte[] {1}, "text/csv"))
        .isInstanceOf(RuntimeException.class);

    LocalExportObjectStore def = new LocalExportObjectStore();
    assertThat(def.createDownloadUrl("x.csv")).contains("exports");

    RedisLiveFeedCache local = new RedisLiveFeedCache(null);
    assertThat(local.get("k")).isEmpty();
    local.put("k", "{\"x\":1}", Duration.ofMillis(1));
    Thread.sleep(5);
    assertThat(local.get("k")).isEmpty(); // expired
    local.put("k2", "v", Duration.ofSeconds(10));
    assertThat(local.get("k2")).contains("v");

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("rk")).thenReturn("cached");
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisLiveFeedCache remote = new RedisLiveFeedCache(provider);
    assertThat(remote.get("rk")).contains("cached");
    remote.put("rk", "v", Duration.ofSeconds(10));
    verify(ops).set(eq("rk"), eq("v"), eq(Duration.ofSeconds(10)));
  }

  private static ResultSet mockOrderDisputeRs(OrderDispute d, Instant now) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(d.id());
    when(rs.getObject("order_id")).thenReturn(d.orderId());
    when(rs.getString("reason")).thenReturn(d.reason());
    when(rs.getString("liable_party")).thenReturn("RIDER");
    when(rs.getObject("flagged_by")).thenReturn(d.flaggedBy());
    when(rs.getTimestamp("flagged_at")).thenReturn(Timestamp.from(now));
    when(rs.getBoolean("resolved")).thenReturn(false);
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(rs.getString("resolution_notes")).thenReturn(null);
    return rs;
  }

  private static ResultSet mockOrderListRs(Instant now) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("order_number")).thenReturn("ORD-1");
    when(rs.getObject("customer_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("cart_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("items"))
        .thenReturn(
            "[{\"product_id\":\""
                + UUID.randomUUID()
                + "\",\"name\":\"M\",\"quantity\":1,\"unit_price_paise\":100,\"line_total_paise\":100,\"rx_required\":false}]");
    when(rs.getLong("item_total_paise")).thenReturn(100L);
    when(rs.getString("coupon_code")).thenReturn(null);
    when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
    when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
    when(rs.getLong("handling_fee_paise")).thenReturn(0L);
    when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
    when(rs.getLong("total_payable_paise")).thenReturn(100L);
    when(rs.getString("payment_method")).thenReturn("COD");
    when(rs.getString("payment_status")).thenReturn("PENDING_COLLECTION");
    when(rs.getString("razorpay_order_id")).thenReturn(null);
    when(rs.getString("razorpay_payment_id")).thenReturn(null);
    when(rs.getObject("prescription_id")).thenReturn(null);
    when(rs.getObject("delivery_address_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("delivery_instructions")).thenReturn(null);
    when(rs.getString("status")).thenReturn("OUT_FOR_DELIVERY");
    when(rs.getObject("rider_id")).thenReturn(null);
    when(rs.getString("delivery_otp_hash")).thenReturn(null);
    when(rs.getString("placement_idempotency_key")).thenReturn(null);
    when(rs.getTimestamp("confirmed_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("estimated_delivery_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("accepted_at")).thenReturn(null);
    when(rs.getTimestamp("delivered_at")).thenReturn(null);
    when(rs.getTimestamp("sla_deadline")).thenReturn(Timestamp.from(now.plusSeconds(120)));
    when(rs.getBoolean("sla_breached")).thenReturn(false);
    when(rs.getTimestamp("rider_assigned_at")).thenReturn(null);
    when(rs.getTimestamp("otp_verified_at")).thenReturn(null);
    when(rs.getTimestamp("ready_for_pickup_at")).thenReturn(null);
    when(rs.getTimestamp("rider_escalation_at")).thenReturn(null);
    when(rs.getString("cancel_reason")).thenReturn(null);
    when(rs.getString("customer_name")).thenReturn("Ravi");
    when(rs.getString("customer_phone")).thenReturn("+91");
    when(rs.getString("pharmacy_name")).thenReturn("Sai");
    when(rs.getString("area")).thenReturn("Koramangala");
    when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("10"));
    when(rs.getBoolean("is_disputed")).thenReturn(true);
    return rs;
  }
}
