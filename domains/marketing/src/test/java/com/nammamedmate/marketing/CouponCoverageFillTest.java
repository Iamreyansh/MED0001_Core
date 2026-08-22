package com.nammamedmate.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.adapter.in.web.AdminCouponController;
import com.nammamedmate.marketing.adapter.out.persistence.JdbcCouponStore;
import com.nammamedmate.marketing.application.CouponService;
import com.nammamedmate.marketing.application.port.out.CouponStore;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID SEG = UUID.fromString("50000001-0000-4000-8000-000000000001");

  @Mock CouponStore store;
  @Mock SegmentStore segments;
  @Mock NotificationDispatchPort notifications;
  CouponService service;

  MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new CouponService(store, segments, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    when(store.findByCodeForUpdate(anyString()))
        .thenAnswer(inv -> store.findByCode(inv.getArgument(0)));
  }

  @Test
  void createValidationBranchesAndPercentageFreeDelivery() {
    assertThatThrownBy(() -> service.create(ops, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        null,
                        "PERCENTAGE",
                        1,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(1),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "  ",
                        "PERCENTAGE",
                        1,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(1),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "X", null, 1, null, null, null, null, null, null, null, null, null, null,
                        null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "X", "  ", 1, null, null, null, null, null, null, null, null, null, null,
                        null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "X",
                        "PERCENTAGE",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        NOW.plusSeconds(10),
                        NOW,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");
    when(store.findByCode(any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "BAD",
                        "PERCENTAGE",
                        0,
                        null,
                        null,
                        null,
                        null,
                        10,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_VALUE");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "BAD",
                        "FLAT_RS",
                        0,
                        null,
                        null,
                        null,
                        null,
                        10,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_VALUE");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "BAD",
                        "PERCENTAGE",
                        25,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        NOW,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.insert(any())).thenAnswer(i -> i.getArgument(0));
    Map<String, Object> pct =
        service.create(
            ops,
            new CouponService.CreateCommand(
                "PCT1",
                "PERCENTAGE",
                10,
                null,
                null,
                null,
                null,
                100,
                List.of(SEG),
                true,
                true,
                null,
                NOW.plusSeconds(100),
                "d",
                "t"));
    assertThat(pct.get("type")).isEqualTo("PERCENTAGE");
    Map<String, Object> free =
        service.create(
            ops,
            new CouponService.CreateCommand(
                "FREE1",
                "FREE_DELIVERY",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                NOW,
                NOW.plusSeconds(100),
                null,
                null));
    assertThat(free.get("type")).isEqualTo("FREE_DELIVERY");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "Z",
                        "NOPE",
                        1,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(1),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void getDetailWithRedemptionsAndListFilters() {
    Coupon c = coupon(CouponType.PERCENTAGE, CouponStatus.ACTIVE, 5, List.of(SEG), 10_000, 5_000);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(c));
    when(store.economics(c.id())).thenReturn(new CouponStore.Economics(1000, 5000));
    when(store.dailyRedemptions(eq(c.id()), anyInt()))
        .thenReturn(
            List.of(new CouponStore.DailyRedemption(java.time.LocalDate.of(2026, 7, 23), 2)));
    when(store.countRedemptions(c.id())).thenReturn(1L);
    when(store.listRedemptions(eq(c.id()), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new CouponStore.RedemptionRow(
                    UUID.randomUUID(), CUST, "Priya", UUID.randomUUID(), 500, NOW)));
    Map<String, Object> detail = service.get(ops, "NAMMA25", 0, 0);
    assertThat(detail.get("redemptions_count")).isEqualTo(5);
    when(store.chips()).thenReturn(new CouponStore.Chips(1, 1, 100, 1000));
    when(store.count(eq(CouponStatus.ACTIVE), eq(CouponType.PERCENTAGE))).thenReturn(1L);
    when(store.list(
            eq(CouponStatus.ACTIVE),
            eq(CouponType.PERCENTAGE),
            eq("budget_used"),
            eq("asc"),
            anyInt(),
            anyInt()))
        .thenReturn(List.of(c));
    assertThat(
            service
                .list(ops, "ACTIVE", "PERCENTAGE", 1, 10, "budget_used", "asc")
                .data()
                .get("coupons"))
        .asList()
        .hasSize(1);
    assertThatThrownBy(() -> service.list(ops, "NOPE", null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void patchValidateAvailableApplyRecordAuthEdges() {
    Coupon c = coupon(CouponType.FLAT_RS, CouponStatus.ACTIVE, 0, List.of(), 1000, 0);
    when(store.findByCode("FLAT50")).thenReturn(Optional.of(c));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    "FLAT50",
                    new CouponService.PatchCommand(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        NOW.plusSeconds(50),
                        NOW,
                        null,
                        null,
                        false)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");
    service.patch(ops, "FLAT50", null);
    verify(store).update(any());

    assertThat(
            service
                .validate(
                    customer, new CouponService.ValidateCommand(null, 100, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_FOUND");
    assertThatThrownBy(
            () ->
                service.validate(
                    customer,
                    new CouponService.ValidateCommand(
                        "FLAT50", 100, UUID.randomUUID(), true, true, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    Coupon expired = coupon(CouponType.FREE_DELIVERY, CouponStatus.EXPIRED, 0, List.of(), 1000, 0);
    when(store.findByCode("FREEDEL")).thenReturn(Optional.of(expired));
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("FREEDEL", 100, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_EXPIRED");
    assertThatThrownBy(() -> service.toggle(ops, "FREEDEL"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Coupon scoped =
        coupon(CouponType.PERCENTAGE, CouponStatus.ACTIVE, 0, List.of(SEG), 1_000_000, 0);
    when(store.list(eq(CouponStatus.ACTIVE), isNull(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                coupon(CouponType.PERCENTAGE, CouponStatus.ACTIVE, 0, List.of(), 100, 100),
                scoped,
                coupon(
                    CouponType.PERCENTAGE,
                    CouponStatus.ACTIVE,
                    0,
                    List.of(),
                    1000,
                    0,
                    NOW.minusSeconds(1000),
                    NOW.minusSeconds(10))));
    when(segments.isMember(SEG, CUST)).thenReturn(true);
    assertThat(service.available(customer, true).meta().total()).isEqualTo(1);

    assertThatThrownBy(() -> service.applyForCart(null, 1000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COUPON");
    Coupon inactive = coupon(CouponType.PERCENTAGE, CouponStatus.PAUSED, 0, List.of(), 1000, 0);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(inactive));
    assertThatThrownBy(() -> service.applyForCart("NAMMA25", 1000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COUPON");
  }

  @Test
  void applyForCartMessagesAndRecordBudgetPause() {
    Coupon pct = coupon(CouponType.PERCENTAGE, CouponStatus.ACTIVE, 0, List.of(), 100_000, 0);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(pct));
    assertThat(service.applyForCart("NAMMA25", 40_000).discountType()).isEqualTo("PERCENT");
    Coupon free = coupon(CouponType.FREE_DELIVERY, CouponStatus.ACTIVE, 0, List.of(), 100_000, 0);
    when(store.findByCode("FREEDEL")).thenReturn(Optional.of(free));
    assertThat(service.applyForCart("FREEDEL", 1000).freeDelivery()).isTrue();

    Coupon tight = coupon(CouponType.FLAT_RS, CouponStatus.ACTIVE, 0, List.of(), 1000, 500);
    when(store.findByCode("FLAT50")).thenReturn(Optional.of(tight));
    service.recordRedemption("FLAT50", UUID.randomUUID(), CUST, 600, 5000);
    ArgumentCaptor<Coupon> cap = ArgumentCaptor.forClass(Coupon.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(CouponStatus.PAUSED);
    verify(notifications).notifyCouponBudgetExhausted(eq("FLAT50"), any());

    when(store.highBurnCouponsForDay(any()))
        .thenReturn(List.of(new CouponStore.BudgetBurnRow("X", 0, 0)));
    service.sendDailyBudgetBurnDigest();

    assertThatThrownBy(() -> service.delete(superAdmin, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COUPON_NOT_FOUND");
    assertThatThrownBy(() -> service.get(null, "X", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcArrayHelpersAndControllerNullBodies() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCouponStore jdbcStore = new JdbcCouponStore(jdbc);
    Coupon c = coupon(CouponType.PERCENTAGE, CouponStatus.ACTIVE, 0, List.of(SEG), 1000, 0);
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    jdbcStore.insert(c);
    jdbcStore.update(
        new Coupon(
            c.id(),
            c.code(),
            c.type(),
            c.percentValue(),
            c.valuePaise(),
            c.minOrderValuePaise(),
            c.maxDiscountCapPaise(),
            c.budgetTotalPaise(),
            c.budgetUsedPaise(),
            c.redemptionsCount(),
            c.maxRedemptionsTotal(),
            c.maxPerUser(),
            List.of(SEG, UUID.randomUUID()),
            false,
            false,
            c.validFrom(),
            c.validUntil(),
            c.status(),
            c.description(),
            c.terms(),
            c.createdBy(),
            c.createdAt(),
            c.updatedAt()));

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(c.id());
    when(rs.getString("code")).thenReturn(c.code());
    when(rs.getString("type")).thenReturn("PERCENTAGE");
    when(rs.getObject("percent_value")).thenReturn(25);
    when(rs.getObject("value_paise")).thenReturn(null);
    when(rs.getLong("min_order_value_paise")).thenReturn(0L);
    when(rs.getObject("max_discount_cap_paise")).thenReturn(null);
    when(rs.getLong("budget_total_paise")).thenReturn(1000L);
    when(rs.getLong("budget_used_paise")).thenReturn(0L);
    when(rs.getInt("redemptions_count")).thenReturn(0);
    when(rs.getObject("max_redemptions_total")).thenReturn(null);
    when(rs.getInt("max_per_user")).thenReturn(1);
    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(new Object[] {SEG.toString(), null});
    when(rs.getArray("segment_ids")).thenReturn(arr);
    when(rs.getBoolean("is_first_order_only")).thenReturn(false);
    when(rs.getBoolean("is_rx_orders_only")).thenReturn(false);
    when(rs.getTimestamp("valid_from")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("valid_until")).thenReturn(Timestamp.from(NOW.plusSeconds(10)));
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getString("description")).thenReturn(null);
    when(rs.getString("terms")).thenReturn(null);
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              return List.of(m.mapRow(rs, 0));
            });
    assertThat(jdbcStore.findByCode("NAMMA25")).isPresent();

    AdminCouponController ctrl = new AdminCouponController(service);
    when(store.findByCode("X")).thenReturn(Optional.of(c));
    when(store.findByCode("Y")).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(i -> i.getArgument(0));
    when(store.economics(any())).thenReturn(new CouponStore.Economics(0, 0));
    when(store.dailyRedemptions(any(), anyInt())).thenReturn(List.of());
    when(store.countRedemptions(any())).thenReturn(0L);
    when(store.listRedemptions(any(), anyInt(), anyInt())).thenReturn(List.of());
    ctrl.get(ops, "X", null, null);
    ctrl.patch(ops, "X", null);
    assertThat(
            ctrl.create(
                    ops,
                    new AdminCouponController.CreateCouponRequest(
                        "Y",
                        "FLAT_RS",
                        10,
                        0,
                        null,
                        null,
                        1,
                        100,
                        List.of(),
                        false,
                        false,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null))
                .getStatusCode()
                .value())
        .isEqualTo(201);
  }

  private static Coupon coupon(
      CouponType type,
      CouponStatus status,
      int redemptions,
      List<UUID> segs,
      long budget,
      long used) {
    return coupon(
        type,
        status,
        redemptions,
        segs,
        budget,
        used,
        NOW.minusSeconds(10),
        NOW.plusSeconds(86_400));
  }

  private static Coupon coupon(
      CouponType type,
      CouponStatus status,
      int redemptions,
      List<UUID> segs,
      long budget,
      long used,
      Instant from,
      Instant until) {
    return new Coupon(
        UUID.fromString("a0130001-0000-4000-8000-000000000001"),
        type == CouponType.FLAT_RS
            ? "FLAT50"
            : type == CouponType.FREE_DELIVERY ? "FREEDEL" : "NAMMA25",
        type,
        type == CouponType.PERCENTAGE ? 25 : null,
        type == CouponType.FLAT_RS ? 5000L : 0L,
        0,
        10_000L,
        budget,
        used,
        redemptions,
        null,
        100,
        segs,
        false,
        false,
        from,
        until,
        status,
        "d",
        "t",
        null,
        NOW,
        NOW);
  }

  @Test
  void remainingBranches() {
    when(store.findByCode(any())).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(i -> i.getArgument(0));
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "NEG",
                        "FLAT_RS",
                        10,
                        null,
                        null,
                        null,
                        null,
                        -1,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_VALUE");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "PCTNULL",
                        "PERCENTAGE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        10,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_VALUE");

    Coupon zeroBudget =
        new Coupon(
            UUID.fromString("a0130001-0000-4000-8000-000000000001"),
            "NAMMA25",
            CouponType.PERCENTAGE,
            null,
            null,
            0,
            null,
            0,
            0,
            2,
            null,
            1,
            List.of(SEG),
            false,
            false,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            "d",
            "t",
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(zeroBudget));
    when(store.economics(any())).thenReturn(new CouponStore.Economics(0, 0));
    when(store.dailyRedemptions(any(), anyInt())).thenReturn(List.of());
    when(store.countRedemptions(any())).thenReturn(0L);
    when(store.listRedemptions(any(), anyInt(), anyInt())).thenReturn(List.of());
    assertThat(service.get(ops, "NAMMA25", 1, 20).get("max_discount_cap")).isNull();

    service.patch(
        ops,
        "NAMMA25",
        new CouponService.PatchCommand(
            10,
            20,
            30,
            5,
            2,
            List.of(SEG),
            true,
            true,
            NOW,
            NOW.plusSeconds(50),
            "nd",
            "nt",
            false));

    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(0);
    when(segments.isMember(SEG, CUST)).thenReturn(true);
    Map<String, Object> ok =
        service.validate(
            customer, new CouponService.ValidateCommand("NAMMA25", null, null, true, true, null));
    assertThat(ok.get("valid")).isEqualTo(true);

    Coupon free =
        new Coupon(
            zeroBudget.id(),
            "FREEDEL",
            CouponType.FREE_DELIVERY,
            null,
            0L,
            0,
            null,
            1000,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("FREEDEL")).thenReturn(Optional.of(free));
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("FREEDEL", 100, CUST, true, true, null))
                .get("applies_to"))
        .isEqualTo("DELIVERY_FEE");

    when(store.list(eq(CouponStatus.ACTIVE), isNull(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new Coupon(
                    free.id(),
                    "FUTURE",
                    CouponType.PERCENTAGE,
                    10,
                    null,
                    0,
                    null,
                    1000,
                    0,
                    0,
                    null,
                    1,
                    List.of(),
                    false,
                    false,
                    NOW.plusSeconds(1000),
                    NOW.plusSeconds(2000),
                    CouponStatus.ACTIVE,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    assertThat(service.available(customer, null).meta().total()).isZero();

    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(zeroBudget));
    assertThat(service.applyForCart("NAMMA25", 10_000).message()).contains("%");

    when(store.findByCode("GONE")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(ops, "GONE", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COUPON_NOT_FOUND");

    when(store.chips()).thenReturn(new CouponStore.Chips(0, 0, 0, 0));
    when(store.count(isNull(), isNull())).thenReturn(1L);
    when(store.list(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
        .thenReturn(List.of(zeroBudget));
    assertThat(service.list(ops, null, null, null, null, null, null).data().get("coupons"))
        .asList()
        .isNotEmpty();

    assertThat(
            new Coupon(
                    free.id(),
                    "X",
                    CouponType.FLAT_RS,
                    null,
                    null,
                    0,
                    null,
                    1,
                    0,
                    0,
                    null,
                    1,
                    null,
                    false,
                    false,
                    NOW,
                    NOW.plusSeconds(1),
                    CouponStatus.ACTIVE,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)
                .apiValue())
        .isEqualTo(0);
    assertThat(
            com.nammamedmate.marketing.domain.CouponDiscount.discountPaise(
                new Coupon(
                    free.id(),
                    "P",
                    CouponType.PERCENTAGE,
                    null,
                    null,
                    0,
                    null,
                    1,
                    0,
                    0,
                    null,
                    1,
                    List.of(),
                    false,
                    false,
                    NOW,
                    NOW.plusSeconds(1),
                    CouponStatus.ACTIVE,
                    null,
                    null,
                    null,
                    NOW,
                    NOW),
                10_000))
        .isZero();

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "BIG",
                        "PERCENTAGE",
                        101,
                        null,
                        null,
                        null,
                        null,
                        10,
                        null,
                        null,
                        null,
                        NOW,
                        NOW.plusSeconds(10),
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_VALUE");

    Coupon future =
        new Coupon(
            free.id(),
            "FUT",
            CouponType.PERCENTAGE,
            25,
            null,
            0,
            10000L,
            1000,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.plusSeconds(1000),
            NOW.plusSeconds(2000),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("FUT")).thenReturn(Optional.of(future));
    assertThat(
            service
                .validate(
                    customer, new CouponService.ValidateCommand("FUT", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_EXPIRED");
    assertThatThrownBy(() -> service.applyForCart("FUT", 5000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COUPON");

    Coupon past =
        new Coupon(
            free.id(),
            "PAST",
            CouponType.PERCENTAGE,
            25,
            null,
            0,
            10000L,
            1000,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.minusSeconds(2000),
            NOW.minusSeconds(1000),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("PAST")).thenReturn(Optional.of(past));
    assertThatThrownBy(() -> service.applyForCart("PAST", 5000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COUPON");
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("PAST", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_EXPIRED");

    Coupon okBudget =
        new Coupon(
            free.id(),
            "OKB",
            CouponType.FLAT_RS,
            null,
            100L,
            0,
            null,
            10_000,
            100,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("OKB")).thenReturn(Optional.of(okBudget));
    service.recordRedemption("OKB", UUID.randomUUID(), CUST, 50, 500);
    service.applyBudgetUsage("OKB", 10);

    when(store.list(eq(CouponStatus.ACTIVE), isNull(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new Coupon(
                    free.id(),
                    "ZEROBUD",
                    CouponType.PERCENTAGE,
                    10,
                    null,
                    0,
                    null,
                    0,
                    0,
                    0,
                    null,
                    1,
                    List.of(),
                    false,
                    false,
                    NOW.minusSeconds(10),
                    NOW.plusSeconds(100),
                    CouponStatus.ACTIVE,
                    "z",
                    null,
                    null,
                    NOW,
                    NOW),
                new Coupon(
                    free.id(),
                    "BURN",
                    CouponType.PERCENTAGE,
                    10,
                    null,
                    0,
                    null,
                    100,
                    100,
                    0,
                    null,
                    1,
                    List.of(),
                    false,
                    false,
                    NOW.minusSeconds(10),
                    NOW.plusSeconds(100),
                    CouponStatus.ACTIVE,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    assertThat(service.available(customer, null).meta().total()).isEqualTo(1);

    // both date bounds false on validate (valid window)
    Coupon windowOk =
        new Coupon(
            free.id(),
            "WIN",
            CouponType.PERCENTAGE,
            10,
            null,
            0,
            null,
            1000,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("WIN")).thenReturn(Optional.of(windowOk));
    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(0);
    assertThat(
            service
                .validate(
                    customer, new CouponService.ValidateCommand("WIN", 100, CUST, true, true, null))
                .get("valid"))
        .isEqualTo(true);

    Coupon rxOnly =
        new Coupon(
            free.id(),
            "RXONLY",
            CouponType.PERCENTAGE,
            10,
            null,
            0,
            null,
            1000,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            true,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("RXONLY")).thenReturn(Optional.of(rxOnly));
    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(0);
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("RXONLY", 500, CUST, true, true, null))
                .get("valid"))
        .isEqualTo(true);

    Coupon zeroTot =
        new Coupon(
            free.id(),
            "ZT",
            CouponType.FLAT_RS,
            null,
            100L,
            0,
            null,
            0,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            NOW.minusSeconds(10),
            NOW.plusSeconds(100),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("ZT")).thenReturn(Optional.of(zeroTot));
    service.recordRedemption("ZT", UUID.randomUUID(), CUST, 10, 100);
    service.applyBudgetUsage("ZT", 10);

    service.list(ops, " ", null, null, null, null, null);
    service.list(ops, null, " ", null, null, null, null);
  }
}
