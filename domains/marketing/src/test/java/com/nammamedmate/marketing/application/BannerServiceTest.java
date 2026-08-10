package com.nammamedmate.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.BannerImageValidatorPort;
import com.nammamedmate.marketing.application.port.out.BannerStore;
import com.nammamedmate.marketing.application.port.out.ImpressionThrottlePort;
import com.nammamedmate.marketing.application.port.out.MarketingAuditPort;
import com.nammamedmate.marketing.domain.Banner;
import com.nammamedmate.marketing.domain.BannerLinkType;
import com.nammamedmate.marketing.domain.BannerPlacement;
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
class BannerServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID BID = UUID.fromString("b0130002-0000-4000-8000-000000000001");
  private static final UUID BID2 = UUID.fromString("b0130002-0000-4000-8000-000000000002");
  private static final UUID BID3 = UUID.fromString("b0130002-0000-4000-8000-000000000003");

  @Mock BannerStore store;
  @Mock BannerImageValidatorPort images;
  @Mock ImpressionThrottlePort throttle;
  @Mock MarketingAuditPort audit;
  BannerService service;

  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "sess-1");

  @BeforeEach
  void setUp() {
    service = new BannerService(store, images, throttle, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac1_createAppearsInAdminAndCustomerList() {
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> created =
        service.create(
            ops,
            new BannerService.CreateCommand(
                "Monsoon Sale",
                "Use NAMMA25",
                "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
                "HOME_TOP",
                "COUPON",
                "NAMMA25",
                "#1A73E8",
                true,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
                1));
    assertThat(created.get("status")).isEqualTo("LIVE");
    verify(images).validate(any());
    verify(audit).append(eq("banner"), eq(ADMIN), any(), any(), eq("CREATE"), isNull(), any());

    Banner b = sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0);
    when(store.count(isNull(), isNull())).thenReturn(1L);
    when(store.list(isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of(b));
    assertThat(service.listAdmin(finance, null, null, null, null).meta().total()).isEqualTo(1);

    when(store.listActiveForPlacement(eq(BannerPlacement.HOME_TOP), eq(NOW)))
        .thenReturn(List.of(b));
    Map<String, Object> cust = service.listCustomer(customer, "HOME_TOP");
    assertThat(((List<?>) cust.get("banners"))).hasSize(1);
  }

  @Test
  void ac2_expiredNotReturnedToCustomerEvenIfLive() {
    when(store.listActiveForPlacement(eq(BannerPlacement.HOME_TOP), eq(NOW))).thenReturn(List.of());
    assertThat(((List<?>) service.listCustomer(customer, "HOME_TOP").get("banners"))).isEmpty();
  }

  @Test
  void ac3_and_ac8_reorderSamePlacementAndMixed() {
    Banner a = sample(BID, BannerPlacement.HOME_TOP, true, 3, 0, 0);
    Banner b = sample(BID2, BannerPlacement.HOME_TOP, true, 2, 0, 0);
    Banner c = sample(BID3, BannerPlacement.HOME_TOP, true, 1, 0, 0);
    when(store.findByIds(List.of(BID, BID2, BID3))).thenReturn(List.of(a, b, c));
    when(store.reorder(anyList(), eq(NOW))).thenReturn(3);
    Map<String, Object> ok =
        service.reorder(
            ops,
            List.of(
                new BannerService.ReorderItem(BID, 1),
                new BannerService.ReorderItem(BID2, 2),
                new BannerService.ReorderItem(BID3, 3)));
    assertThat(ok.get("updated_count")).isEqualTo(3);
    verify(audit).append(eq("banner"), any(), any(), any(), eq("REORDER"), any(), any());

    Banner other = sample(BID2, BannerPlacement.OFFERS, true, 1, 0, 0);
    when(store.findByIds(List.of(BID, BID2))).thenReturn(List.of(a, other));
    assertThatThrownBy(
            () ->
                service.reorder(
                    ops,
                    List.of(
                        new BannerService.ReorderItem(BID, 1),
                        new BannerService.ReorderItem(BID2, 2))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MIXED_PLACEMENTS");
  }

  @Test
  void ac4_impressionThrottled() {
    when(store.findById(BID))
        .thenReturn(Optional.of(sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0)));
    when(throttle.tryAcquire(eq(BID), eq(CUST), eq("sess-1"))).thenReturn(true, false);
    assertThat(service.logImpression(customer, BID, null).get("logged")).isEqualTo(true);
    assertThat(service.logImpression(customer, BID, "sess-1").get("logged")).isEqualTo(true);
    verify(store, times(1)).incrementImpressions(BID);
  }

  @Test
  void ac5_ctrMatchesCounts() {
    Banner b = sample(BID, BannerPlacement.HOME_TOP, true, 1, 128400, 6420);
    when(store.count(isNull(), isNull())).thenReturn(1L);
    when(store.list(isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of(b));
    Map<String, Object> item =
        ((List<Map<String, Object>>)
                service.listAdmin(finance, null, null, 1, 20).data().get("banners"))
            .get(0);
    assertThat(item.get("ctr_pct")).isEqualTo(new BigDecimal("5.0"));
    assertThat(item.get("impressions")).isEqualTo(128400L);
    assertThat(item.get("clicks")).isEqualTo(6420L);
  }

  @Test
  void ac6_toggleTakesOffline() {
    when(store.findById(BID))
        .thenReturn(Optional.of(sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0)));
    Map<String, Object> toggled = service.toggle(ops, BID);
    assertThat(toggled.get("is_live")).isEqualTo(false);
    ArgumentCaptor<Banner> cap = ArgumentCaptor.forClass(Banner.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().live()).isFalse();
  }

  @Test
  void ac7_invalidImageUrl() {
    org.mockito.Mockito.doThrow(
            new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422))
        .when(images)
        .validate(any());
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "Bad",
                        null,
                        "https://cdn.nammamedmate.com/banners/missing-404.jpg",
                        "HOME_TOP",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(3600),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    verify(store, never()).insert(any());
  }

  @Test
  void ac9_deactivateExpiredJob() {
    when(store.deactivateExpired(NOW)).thenReturn(2);
    assertThat(service.deactivateExpired()).isEqualTo(2);
  }

  @Test
  void ac10_auditOnMutations() {
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    service.create(
        ops,
        new BannerService.CreateCommand(
            "H",
            null,
            "https://cdn.test/a.jpg",
            "HOME_TOP",
            "EXTERNAL_URL",
            "https://x.com",
            null,
            true,
            NOW,
            NOW.plusSeconds(60),
            null));
    when(store.findById(BID))
        .thenReturn(Optional.of(sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0)));
    service.patch(
        ops,
        BID,
        new BannerService.PatchCommand(
            "H2", null, null, null, null, null, null, null, null, null, 2));
    service.toggle(ops, BID);
    when(store.findByIds(List.of(BID)))
        .thenReturn(List.of(sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0)));
    when(store.reorder(anyList(), eq(NOW))).thenReturn(1);
    service.reorder(ops, List.of(new BannerService.ReorderItem(BID, 1)));
    service.delete(superAdmin, BID);
    verify(audit, times(5))
        .append(
            any(),
            any(),
            any(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.nullable(Map.class),
            any());
  }

  @Test
  void deleteNotFoundAndClick() {
    when(store.findById(BID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(superAdmin, BID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BANNER_NOT_FOUND");

    when(store.findById(BID))
        .thenReturn(Optional.of(sample(BID, BannerPlacement.HOME_TOP, true, 1, 0, 0)));
    assertThat(service.logClick(customer, BID).get("logged")).isEqualTo(true);
    verify(store).incrementClicks(BID);
  }

  private static Banner sample(
      UUID id,
      BannerPlacement placement,
      boolean live,
      int priority,
      long impressions,
      long clicks) {
    return new Banner(
        id,
        "Monsoon Sale",
        "sub",
        "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
        placement,
        BannerLinkType.COUPON,
        "NAMMA25",
        "#1A73E8",
        live,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-12-31T23:59:59Z"),
        priority,
        impressions,
        clicks,
        ADMIN,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-01T00:00:00Z"));
  }
}
