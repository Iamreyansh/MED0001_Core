package com.nammamedmate.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.CouponStore;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
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

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID SEG = UUID.fromString("50000001-0000-4000-8000-000000000001");

  @Mock CouponStore store;
  @Mock SegmentStore segments;
  @Mock NotificationDispatchPort notifications;
  CouponService service;

  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new CouponService(store, segments, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac1_createAndList() {
    when(store.findByCode("FLAT50")).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> created =
        service.create(
            ops,
            new CouponService.CreateCommand(
                "flat50",
                "FLAT_RS",
                50,
                399,
                50,
                5000,
                1,
                250000,
                List.of(),
                false,
                false,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-31T23:59:59Z"),
                "desc",
                "terms"));
    assertThat(created.get("code")).isEqualTo("FLAT50");
    assertThat(created.get("status")).isEqualTo("ACTIVE");

    when(store.chips()).thenReturn(new CouponStore.Chips(1, 0, 0, 25_000_000));
    when(store.count(isNull(), isNull())).thenReturn(1L);
    when(store.list(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
        .thenReturn(List.of(namma25()));
    CouponService.PagedResult listed = service.list(finance, null, null, null, null, null, null);
    assertThat(listed.meta().total()).isEqualTo(1);
    assertThat(((List<?>) listed.data().get("coupons"))).hasSize(1);
  }

  @Test
  void ac2_duplicateCode() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CouponService.CreateCommand(
                        "NAMMA25",
                        "PERCENTAGE",
                        25,
                        199,
                        100,
                        null,
                        1,
                        50000,
                        null,
                        false,
                        false,
                        NOW,
                        NOW.plusSeconds(86400),
                        null,
                        null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COUPON_CODE_EXISTS");
  }

  @Test
  void ac3_validateNamma25Cap() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(0);
    Map<String, Object> data =
        service.validate(
            customer, new CouponService.ValidateCommand("namma25", 580, CUST, false, false, null));
    assertThat(data.get("valid")).isEqualTo(true);
    assertThat((BigDecimal) data.get("discount_amount"))
        .isEqualByComparingTo(new BigDecimal("100.00"));
  }

  @Test
  void ac4_minOrderNotMet() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    Map<String, Object> data =
        service.validate(
            customer, new CouponService.ValidateCommand("NAMMA25", 100, CUST, false, false, null));
    assertThat(data.get("valid")).isEqualTo(false);
    assertThat(data.get("error_code")).isEqualTo("COUPON_MIN_ORDER_NOT_MET");
  }

  @Test
  void ac5_budgetAutoPauseNotifies() {
    Coupon c =
        new Coupon(
            namma25().id(),
            "NAMMA25",
            CouponType.PERCENTAGE,
            25,
            null,
            19900,
            10000L,
            10_000,
            9_000,
            1,
            null,
            100,
            List.of(),
            false,
            false,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.ACTIVE,
            "d",
            "t",
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(c));
    service.applyBudgetUsage("NAMMA25", 2_000);
    ArgumentCaptor<Coupon> cap = ArgumentCaptor.forClass(Coupon.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(CouponStatus.PAUSED);
    verify(notifications).notifyCouponBudgetExhausted("NAMMA25", c.id());
  }

  @Test
  void ac6_immutablePatch() {
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    "NAMMA25",
                    new CouponService.PatchCommand(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        true)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IMMUTABLE_FIELD");
  }

  @Test
  void ac7_deleteHardAndExpire() {
    Coupon zero = namma25();
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(zero));
    Map<String, Object> deleted = service.delete(superAdmin, "NAMMA25");
    assertThat(deleted.get("action")).isEqualTo("DELETED");
    verify(store).hardDelete(zero.id());

    Coupon used =
        new Coupon(
            zero.id(),
            zero.code(),
            zero.type(),
            zero.percentValue(),
            zero.valuePaise(),
            zero.minOrderValuePaise(),
            zero.maxDiscountCapPaise(),
            zero.budgetTotalPaise(),
            zero.budgetUsedPaise(),
            5,
            zero.maxRedemptionsTotal(),
            zero.maxPerUser(),
            zero.segmentIds(),
            false,
            false,
            zero.validFrom(),
            zero.validUntil(),
            CouponStatus.ACTIVE,
            zero.description(),
            zero.terms(),
            null,
            NOW,
            NOW);
    when(store.findByCode("FLAT50")).thenReturn(Optional.of(used));
    Map<String, Object> expired = service.delete(superAdmin, "FLAT50");
    assertThat(expired.get("action")).isEqualTo("EXPIRED");
    verify(store).update(any());
  }

  @Test
  void ac8_availableFiltersSegment() {
    Coupon open = namma25();
    Coupon scoped =
        new Coupon(
            UUID.fromString("a0130001-0000-4000-8000-000000000099"),
            "VIP10",
            CouponType.PERCENTAGE,
            10,
            null,
            0,
            null,
            1_000_000,
            0,
            0,
            null,
            1,
            List.of(SEG),
            false,
            false,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.ACTIVE,
            "vip",
            null,
            null,
            NOW,
            NOW);
    when(store.list(
            eq(CouponStatus.ACTIVE), isNull(), eq("created_at"), eq("desc"), eq(0), eq(200)))
        .thenReturn(List.of(open, scoped));
    when(segments.isMember(SEG, CUST)).thenReturn(false);
    CouponService.PagedResult page = service.available(customer, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> coupons = (List<Map<String, Object>>) page.data().get("coupons");
    assertThat(coupons).hasSize(1);
    assertThat(coupons.getFirst().get("code")).isEqualTo("NAMMA25");
  }

  @Test
  void ac9_toggle() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    Map<String, Object> paused = service.toggle(ops, "NAMMA25");
    assertThat(paused.get("status")).isEqualTo("PAUSED");
    Coupon pausedCoupon =
        new Coupon(
            namma25().id(),
            "NAMMA25",
            CouponType.PERCENTAGE,
            25,
            null,
            19900,
            10000L,
            5000000000L,
            0,
            0,
            null,
            100,
            List.of(),
            false,
            false,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.PAUSED,
            "d",
            "t",
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(pausedCoupon));
    assertThat(service.toggle(ops, "NAMMA25").get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void ac10_roasArithmetic() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    when(store.economics(namma25().id()))
        .thenReturn(new CouponStore.Economics(18_250_00L, 292_000_00L));
    when(store.dailyRedemptions(any(), anyInt())).thenReturn(List.of());
    when(store.countRedemptions(any())).thenReturn(0L);
    when(store.listRedemptions(any(), anyInt(), anyInt())).thenReturn(List.of());
    Map<String, Object> detail = service.get(finance, "NAMMA25", 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> eco = (Map<String, Object>) detail.get("economics");
    assertThat((BigDecimal) eco.get("roas")).isEqualByComparingTo(new BigDecimal("16.00"));
  }

  @Test
  void applyForCartAndRecordRedemptionAndDigest() {
    when(store.findByCode("FLAT50")).thenReturn(Optional.of(flat50()));
    CouponService.CartQuote q = service.applyForCart("flat50", 40_000);
    assertThat(q.discountType()).isEqualTo("FLAT");
    assertThat(q.discountPaise()).isEqualTo(5_000L);

    when(store.findByCode("FLAT50")).thenReturn(Optional.of(flat50()));
    service.recordRedemption("FLAT50", UUID.randomUUID(), CUST, 5_000, 40_000);
    verify(store).insertRedemption(any(), any(), any(), eq(CUST), eq(5_000L), eq(40_000L), any());

    when(store.highBurnCouponsForDay(any()))
        .thenReturn(List.of(new CouponStore.BudgetBurnRow("NAMMA25", 10000L, 8000)));
    service.sendDailyBudgetBurnDigest();
    verify(notifications).notifyDailyBudgetBurnDigest(any());

    when(store.highBurnCouponsForDay(any())).thenReturn(List.of());
    service.sendDailyBudgetBurnDigest();
  }

  @Test
  void validateErrorBranches() {
    when(store.findByCode("GONE")).thenReturn(Optional.empty());
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("GONE", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_FOUND");

    Coupon paused =
        new Coupon(
            namma25().id(),
            "NAMMA25",
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
            List.of(SEG),
            true,
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.PAUSED,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(paused));
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_PAUSED");

    Coupon activeSeg =
        new Coupon(
            paused.id(),
            "NAMMA25",
            CouponType.PERCENTAGE,
            25,
            null,
            0,
            10000L,
            1000,
            1000,
            0,
            null,
            1,
            List.of(SEG),
            true,
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(activeSeg));
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_BUDGET_EXHAUSTED");

    Coupon okBudget =
        new Coupon(
            paused.id(),
            "NAMMA25",
            CouponType.PERCENTAGE,
            25,
            null,
            0,
            10000L,
            100000,
            0,
            0,
            null,
            1,
            List.of(SEG),
            true,
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2099-12-31T23:59:59Z"),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(okBudget));
    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(1);
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_PER_USER_LIMIT");

    when(store.countRedemptionsForCustomer(any(), eq(CUST))).thenReturn(0);
    when(segments.isMember(SEG, CUST)).thenReturn(false);
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, true, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_SEGMENT_MISMATCH");

    when(segments.isMember(SEG, CUST)).thenReturn(true);
    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, false, true, null))
                .get("error_code"))
        .isEqualTo("COUPON_FIRST_ORDER_ONLY");

    assertThat(
            service
                .validate(
                    customer,
                    new CouponService.ValidateCommand("NAMMA25", 500, CUST, true, false, null))
                .get("error_code"))
        .isEqualTo("COUPON_RX_ONLY");
  }

  @Test
  void patchToggleDeleteAuthAndCartMin() {
    when(store.findByCode("NAMMA25")).thenReturn(Optional.of(namma25()));
    service.patch(
        ops,
        "NAMMA25",
        new CouponService.PatchCommand(
            299,
            120,
            75000,
            null,
            2,
            null,
            null,
            null,
            null,
            NOW.plusSeconds(99999),
            "d",
            "t",
            false));
    verify(store).update(any());

    assertThatThrownBy(() -> service.delete(ops, "NAMMA25"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(store.findByCode("FLAT50")).thenReturn(Optional.of(flat50()));
    assertThatThrownBy(() -> service.applyForCart("FLAT50", 1000))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COUPON_MIN_NOT_MET");
    when(store.findByCode("NOPE")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.applyForCart("NOPE", 1000))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COUPON");
  }

  private static Coupon namma25() {
    return new Coupon(
        UUID.fromString("a0130001-0000-4000-8000-000000000001"),
        "NAMMA25",
        CouponType.PERCENTAGE,
        25,
        null,
        19900,
        10000L,
        5000000000L,
        0,
        0,
        null,
        100,
        List.of(),
        false,
        false,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2099-12-31T23:59:59Z"),
        CouponStatus.ACTIVE,
        "25% off",
        "terms",
        null,
        NOW,
        NOW);
  }

  private static Coupon flat50() {
    return new Coupon(
        UUID.fromString("a0130001-0000-4000-8000-000000000002"),
        "FLAT50",
        CouponType.FLAT_RS,
        null,
        5000L,
        39900,
        5000L,
        2500000000L,
        0,
        0,
        null,
        100,
        List.of(),
        false,
        false,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2099-12-31T23:59:59Z"),
        CouponStatus.ACTIVE,
        "flat",
        "terms",
        null,
        NOW,
        NOW);
  }
}
