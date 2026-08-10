package com.nammamedmate.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.adapter.in.web.AdminBannerController;
import com.nammamedmate.marketing.adapter.out.cache.RedisImpressionThrottle;
import com.nammamedmate.marketing.adapter.out.client.StubBannerImageValidator;
import com.nammamedmate.marketing.adapter.out.persistence.JdbcBannerStore;
import com.nammamedmate.marketing.adapter.out.persistence.JdbcMarketingAuditAdapter;
import com.nammamedmate.marketing.application.BannerService;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class BannerCoverageGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID BID = UUID.fromString("b0130002-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Mock BannerStore store;
  @Mock BannerImageValidatorPort images;
  @Mock ImpressionThrottlePort throttle;
  @Mock MarketingAuditPort audit;
  BannerService service;

  MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new BannerService(store, images, throttle, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullBodiesToggleOfflineCreateFalseLiveBlankLinkType() throws Exception {
    AdminBannerController admin = new AdminBannerController(service);
    assertThatThrownBy(() -> admin.create(ops, null)).isInstanceOf(AppException.class);
    when(store.findById(BID)).thenReturn(Optional.of(sample(false)));
    assertThatCode(() -> admin.patch(ops, BID, null)).doesNotThrowAnyException();

    Map<String, Object> toggled = service.toggle(ops, BID);
    assertThat(toggled.get("is_live")).isEqualTo(true);

    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> offline =
        service.create(
            ops,
            new BannerService.CreateCommand(
                "H",
                null,
                "https://cdn.test/a.jpg",
                "HOME_MID",
                "PHARMACY",
                "p1",
                null,
                false,
                NOW,
                NOW.plusSeconds(10),
                2));
    assertThat(offline.get("status")).isEqualTo("OFFLINE");

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        " ",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.count(isNull(), isNull())).thenReturn(0L);
    when(store.list(isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of());
    assertThat(service.listAdmin(ops, "  ", null, null, null).meta().total()).isEqualTo(0);

    StubBannerImageValidator stub = new StubBannerImageValidator();
    stub.validate("https://cdn.test/a.jpg?v=1");
    stub.validate("https://foo.jpg");
    assertThatThrownBy(() -> stub.validate("https://cdn.test/over2mb.png"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IMAGE_TOO_LARGE");
    assertThatThrownBy(() -> stub.validate("https://cdn.nammamedmate.com/x?404=1.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");

    RedisImpressionThrottle nullProvider = new RedisImpressionThrottle(null);
    assertThat(nullProvider.tryAcquire(BID, CUST, "sx")).isTrue();

    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);
    JdbcBannerStore jdbcStore = new JdbcBannerStore(jdbc);
    assertThat(jdbcStore.incrementClicks(BID)).isFalse();

    JdbcMarketingAuditAdapter auditAdapter =
        new JdbcMarketingAuditAdapter(jdbc, new ObjectMapper());
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    auditAdapter.append(null, ADMIN, "ADMIN_SUPER", BID, "CREATE", Map.of("a", 1), null);
    auditAdapter.append("", ADMIN, "ADMIN_SUPER", BID, "CREATE", null, null);

    // remaining branch/line gaps
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        null,
                        "HOME_TOP",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "COUPON",
                        null,
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        null,
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        "https://cdn.test/a.jpg",
                        "  ",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PLACEMENT");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "H",
                        null,
                        "https://cdn.test/a.jpg",
                        null,
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(1),
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PLACEMENT");
    assertThatThrownBy(
            () -> service.reorder(ops, java.util.Arrays.asList((BannerService.ReorderItem) null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    MedmatePrincipal customer =
        new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-x");
    when(store.findById(BID)).thenReturn(Optional.of(sample(true)));
    when(throttle.tryAcquire(any(), any(), any())).thenReturn(false);
    assertThat(service.logImpression(customer, BID, "  ").get("logged")).isEqualTo(true);

    when(store.count(isNull(), isNull())).thenReturn(0L);
    when(store.list(isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of());
    assertThat(service.listAdmin(ops, null, null, -1, -5).meta().limit()).isEqualTo(20);

    assertThatThrownBy(() -> admin.reorder(ops, new AdminBannerController.ReorderRequest(null)))
        .isInstanceOf(AppException.class);

    assertThatThrownBy(() -> stub.validate("https://cdn.test/missing/file.png"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");

    // expire in-memory throttle entry
    var field = RedisImpressionThrottle.class.getDeclaredField("local");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentHashMap<String, Long> local =
        (java.util.concurrent.ConcurrentHashMap<String, Long>) field.get(nullProvider);
    String key = "banner:imp:" + BID + ':' + CUST + ":sx";
    local.put(key, System.currentTimeMillis() - java.time.Duration.ofMinutes(30).toMillis() - 1);
    assertThat(nullProvider.tryAcquire(BID, CUST, "sx")).isTrue();
  }

  private Banner sample(boolean live) {
    return new Banner(
        BID,
        "H",
        "s",
        "https://cdn.test/a.jpg",
        BannerPlacement.HOME_TOP,
        BannerLinkType.COUPON,
        "X",
        null,
        live,
        NOW.minusSeconds(10),
        NOW.plusSeconds(100),
        1,
        0,
        0,
        ADMIN,
        NOW,
        NOW);
  }
}
