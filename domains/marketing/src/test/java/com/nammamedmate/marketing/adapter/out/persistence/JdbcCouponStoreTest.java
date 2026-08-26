package com.nammamedmate.marketing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import java.sql.Array;
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
class JdbcCouponStoreTest {

  @Mock JdbcTemplate jdbc;
  JdbcCouponStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcCouponStore(jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void coversMutationsAndQueries() throws Exception {
    Coupon c = sample();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.insert(c);
    store.update(c);
    store.hardDelete(c.id());
    store.insertRedemption(
        UUID.randomUUID(), c.id(), UUID.randomUUID(), UUID.randomUUID(), 100, 1000, Instant.now());
    verify(jdbc, org.mockito.Mockito.atLeast(4)).update(anyString(), any(Object[].class));

    ResultSet rs = mockRs(c);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findByCode("NAMMA25")).isPresent();
    assertThat(store.findByCodeForUpdate("NAMMA25")).isPresent();
    when(rs.getArray("segment_ids")).thenReturn(null);
    assertThat(store.findById(c.id())).isPresent();
    Array bad = mock(Array.class);
    when(bad.getArray()).thenReturn("nope");
    when(rs.getArray("segment_ids")).thenReturn(bad);
    assertThat(store.findById(c.id())).isPresent();
    Array mixed = mock(Array.class);
    when(mixed.getArray())
        .thenReturn(new Object[] {UUID.randomUUID(), "50000001-0000-4000-8000-000000000001", null});
    when(rs.getArray("segment_ids")).thenReturn(mixed);
    assertThat(store.findById(c.id())).isPresent();

    assertThat(store.list(CouponStatus.ACTIVE, CouponType.PERCENTAGE, "code", "asc", 0, 10))
        .isNotEmpty();
    assertThat(store.list(null, null, "budget_used_paise", "ASC", 0, 10)).isNotEmpty();
    assertThat(store.list(null, null, "redemptions", null, 0, 10)).isNotEmpty();
    assertThat(store.listRedemptions(c.id(), 0, 10)).isNotEmpty();
    assertThat(store.dailyRedemptions(c.id(), 5)).isNotEmpty();
    assertThat(store.economics(c.id())).isNotNull();
    assertThat(store.highBurnCouponsForDay(LocalDate.of(2026, 7, 24))).isNotEmpty();
    assertThat(store.chips().activeCount()).isEqualTo(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.count(null, null)).isEqualTo(0);
    assertThat(store.countRedemptions(c.id())).isEqualTo(0);
    assertThat(store.isSegmentReferencedByActiveCoupon(UUID.randomUUID())).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    assertThat(store.count(CouponStatus.ACTIVE, CouponType.PERCENTAGE)).isEqualTo(2);
    assertThat(store.countRedemptions(c.id())).isEqualTo(2);
    assertThat(store.isSegmentReferencedByActiveCoupon(UUID.randomUUID())).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
        .thenReturn(null)
        .thenReturn(3);
    assertThat(store.countRedemptionsForCustomer(c.id(), UUID.randomUUID())).isEqualTo(0);
    assertThat(store.countRedemptionsForCustomer(c.id(), UUID.randomUUID())).isEqualTo(3);
    assertThat(store.list(null, null, "redemptions_count", "desc", 0, 1)).isNotEmpty();
    assertThat(store.list(null, null, "budget_used", "desc", 0, 1)).isNotEmpty();
    assertThat(store.list(null, null, null, null, 0, 1)).isNotEmpty();
  }

  private static ResultSet mockRs(Coupon c) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(c.id());
    when(rs.getString("code")).thenReturn(c.code());
    when(rs.getString("type")).thenReturn(c.type().name());
    when(rs.getObject("percent_value")).thenReturn(c.percentValue());
    when(rs.getObject("value_paise")).thenReturn(c.valuePaise());
    when(rs.getLong("min_order_value_paise")).thenReturn(c.minOrderValuePaise());
    when(rs.getObject("max_discount_cap_paise")).thenReturn(c.maxDiscountCapPaise());
    when(rs.getLong("budget_total_paise")).thenReturn(c.budgetTotalPaise());
    when(rs.getLong("budget_used_paise")).thenReturn(c.budgetUsedPaise());
    when(rs.getInt("redemptions_count")).thenReturn(c.redemptionsCount());
    when(rs.getObject("max_redemptions_total")).thenReturn(c.maxRedemptionsTotal());
    when(rs.getInt("max_per_user")).thenReturn(c.maxPerUser());
    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(new UUID[] {UUID.randomUUID()});
    when(rs.getArray("segment_ids")).thenReturn(arr);
    when(rs.getBoolean("is_first_order_only")).thenReturn(false);
    when(rs.getBoolean("is_rx_orders_only")).thenReturn(false);
    when(rs.getTimestamp("valid_from")).thenReturn(Timestamp.from(c.validFrom()));
    when(rs.getTimestamp("valid_until")).thenReturn(Timestamp.from(c.validUntil()));
    when(rs.getString("status")).thenReturn(c.status().name());
    when(rs.getString("description")).thenReturn(c.description());
    when(rs.getString("terms")).thenReturn(c.terms());
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(c.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(c.updatedAt()));
    when(rs.getInt("active_count")).thenReturn(1);
    when(rs.getLong("total_redemptions")).thenReturn(0L);
    when(rs.getLong("discount_spend")).thenReturn(0L);
    when(rs.getLong("marketing_spend")).thenReturn(0L);
    when(rs.getLong("revenue")).thenReturn(0L);
    when(rs.getObject("d", LocalDate.class)).thenReturn(LocalDate.of(2026, 7, 24));
    when(rs.getInt("c")).thenReturn(1);
    when(rs.getObject("customer_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("customer_name")).thenReturn("A");
    when(rs.getObject("order_id")).thenReturn(UUID.randomUUID());
    when(rs.getLong("discount_applied_paise")).thenReturn(100L);
    when(rs.getTimestamp("redeemed_at")).thenReturn(Timestamp.from(Instant.now()));
    return rs;
  }

  private static Coupon sample() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    return new Coupon(
        UUID.fromString("a0130001-0000-4000-8000-000000000001"),
        "NAMMA25",
        CouponType.PERCENTAGE,
        25,
        null,
        19900,
        10000L,
        1000,
        0,
        0,
        null,
        1,
        List.of(),
        false,
        false,
        now,
        now.plusSeconds(86400),
        CouponStatus.ACTIVE,
        "d",
        "t",
        null,
        now,
        now);
  }
}
