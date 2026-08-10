package com.nammamedmate.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.adapter.in.web.AdminBannerController;
import com.nammamedmate.marketing.adapter.in.web.CustomerBannerController;
import com.nammamedmate.marketing.adapter.out.cache.RedisImpressionThrottle;
import com.nammamedmate.marketing.adapter.out.client.StubBannerImageValidator;
import com.nammamedmate.marketing.adapter.out.persistence.JdbcBannerStore;
import com.nammamedmate.marketing.application.BannerExpiryScheduler;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BannerCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID BID = UUID.fromString("b0130002-0000-4000-8000-000000000001");

  @Mock BannerStore store;
  @Mock BannerImageValidatorPort images;
  @Mock ImpressionThrottlePort throttle;
  @Mock MarketingAuditPort audit;
  BannerService service;

  MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  MedmatePrincipal pharmacy =
      new MedmatePrincipal(ADMIN, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new BannerService(store, images, throttle, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createValidationBranches() {
    assertThatThrownBy(() -> service.create(ops, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        " ", null, null, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "x".repeat(121),
                        null,
                        "https://cdn.test/a.jpg",
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
                        "ok",
                        "y".repeat(201),
                        "https://cdn.test/a.jpg",
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
                        "ok",
                        null,
                        " ",
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
                        "ok",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "COUPON",
                        " ",
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
                        "ok",
                        null,
                        "https://cdn.test/a.jpg",
                        "BAD",
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
                        "ok",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "NOPE",
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
                        "ok",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        null,
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new BannerService.CreateCommand(
                        "ok",
                        null,
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW.plusSeconds(10),
                        NOW,
                        1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.create(pharmacy, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listAdmin(null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void patchReorderAndListFilters() {
    Banner existing = sample();
    when(store.findById(BID)).thenReturn(Optional.of(existing));
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    service.patch(ops, BID, null);
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        " ", null, null, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        "x".repeat(121),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        "ok",
                        "y".repeat(201),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    doThrow(new AppException("IMAGE_TOO_LARGE", "Image exceeds 2 MB", 422))
        .when(images)
        .validate(eq("https://cdn.test/too-large.png"));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        null,
                        null,
                        "https://cdn.test/too-large.png",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IMAGE_TOO_LARGE");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        null, null, null, null, null, " ", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    BID,
                    new BannerService.PatchCommand(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        NOW.plusSeconds(100),
                        NOW,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");
    service.patch(
        ops,
        BID,
        new BannerService.PatchCommand(
            "New",
            "  ",
            "https://cdn.test/b.png",
            "OFFERS",
            "TELECONSULT",
            "book",
            "#fff",
            false,
            NOW,
            NOW.plusSeconds(50),
            5));

    assertThatThrownBy(() -> service.reorder(ops, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reorder(ops, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reorder(ops, List.of(new BannerService.ReorderItem(null, 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.reorder(ops, List.of(new BannerService.ReorderItem(BID, null))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findByIds(List.of(BID))).thenReturn(List.of());
    assertThatThrownBy(() -> service.reorder(ops, List.of(new BannerService.ReorderItem(BID, 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BANNER_NOT_FOUND");

    when(store.count(eq(BannerPlacement.HOME_TOP), eq(true))).thenReturn(0L);
    when(store.list(eq(BannerPlacement.HOME_TOP), eq(true), eq(0), eq(100))).thenReturn(List.of());
    assertThat(service.listAdmin(ops, "HOME_TOP", true, 0, 999).meta().limit()).isEqualTo(100);

    assertThatThrownBy(() -> service.listCustomer(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PLACEMENT");
    assertThatThrownBy(() -> service.delete(ops, BID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    doThrow(new RuntimeException("audit down"))
        .when(audit)
        .append(any(), any(), any(), any(), any(), nullable(Map.class), any());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    service.create(
        ops,
        new BannerService.CreateCommand(
            "ok",
            "sub",
            "https://cdn.test/a.jpg",
            "HOME_TOP",
            "COUPON",
            "X",
            "  ",
            null,
            null,
            NOW.plusSeconds(1),
            null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void controllersSchedulerStubThrottleJdbc() throws Exception {
    when(store.count(isNull(), isNull())).thenReturn(0L);
    when(store.list(isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of());
    AdminBannerController admin = new AdminBannerController(service);
    assertThat(admin.list(ops, null, null, null, null).success()).isTrue();
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(
            admin
                .create(
                    ops,
                    new AdminBannerController.CreateBannerRequest(
                        "H",
                        "s",
                        "https://cdn.test/a.jpg",
                        "HOME_TOP",
                        "COUPON",
                        "X",
                        null,
                        true,
                        NOW,
                        NOW.plusSeconds(60),
                        1))
                .getStatusCode()
                .value())
        .isEqualTo(201);
    when(store.findById(BID)).thenReturn(Optional.of(sample()));
    assertThat(
            admin
                .patch(
                    ops,
                    BID,
                    new AdminBannerController.PatchBannerRequest(
                        "H", null, null, null, null, null, null, null, null, null, null))
                .success())
        .isTrue();
    assertThat(admin.toggle(ops, BID).success()).isTrue();
    when(store.findByIds(anyList())).thenReturn(List.of(sample()));
    when(store.reorder(anyList(), eq(NOW))).thenReturn(1);
    assertThat(
            admin
                .reorder(
                    ops,
                    new AdminBannerController.ReorderRequest(
                        List.of(new AdminBannerController.ReorderItemRequest(BID, 1))))
                .success())
        .isTrue();
    assertThat(admin.delete(superAdmin, BID).data().get("deleted")).isEqualTo(true);

    assertThatThrownBy(() -> admin.reorder(ops, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                admin.reorder(
                    ops,
                    new AdminBannerController.ReorderRequest(
                        java.util.Arrays.asList((AdminBannerController.ReorderItemRequest) null))))
        .isInstanceOf(AppException.class);

    when(store.listActiveForPlacement(any(), eq(NOW))).thenReturn(List.of(sample()));
    when(throttle.tryAcquire(any(), any(), any())).thenReturn(true);
    CustomerBannerController cust = new CustomerBannerController(service);
    assertThat(cust.list(customer, "HOME_TOP", 1.0, 2.0).success()).isTrue();
    assertThat(cust.impression(customer, BID, "s1").success()).isTrue();
    assertThat(cust.click(customer, BID).success()).isTrue();

    BannerExpiryScheduler sched = new BannerExpiryScheduler(service);
    when(store.deactivateExpired(NOW)).thenReturn(1);
    sched.deactivateExpired();
    verify(store).deactivateExpired(NOW);

    StubBannerImageValidator stub = new StubBannerImageValidator();
    stub.validate("https://cdn.nammamedmate.com/banners/a.jpg");
    stub.validate("https://cdn.test:8443/x.PNG");
    stub.validate("https://other.example.com/pic.jpeg");
    assertThatThrownBy(() -> stub.validate(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> stub.validate("http://cdn.test/a.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> stub.validate("https://cdn.test/missing-404.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> stub.validate("https://cdn.test/too-large.png"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IMAGE_TOO_LARGE");
    assertThatThrownBy(() -> stub.validate("https://cdn.test/a.gif"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> stub.validate(" "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");

    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisImpressionThrottle mem = new RedisImpressionThrottle(provider);
    assertThat(mem.tryAcquire(null, CUST, "s")).isFalse();
    assertThat(mem.tryAcquire(BID, null, "s")).isFalse();
    assertThat(mem.tryAcquire(BID, CUST, null)).isTrue();
    assertThat(mem.tryAcquire(BID, CUST, " ")).isFalse();

    StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> opsVal = Mockito.mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(opsVal);
    when(opsVal.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisImpressionThrottle withRedis = new RedisImpressionThrottle(provider);
    assertThat(withRedis.tryAcquire(BID, CUST, "s2")).isTrue();
    when(opsVal.setIfAbsent(any(), eq("1"), any())).thenReturn(false);
    assertThat(withRedis.tryAcquire(BID, CUST, "s2b")).isFalse();
    when(opsVal.setIfAbsent(any(), eq("1"), any())).thenThrow(new RuntimeException("down"));
    assertThat(withRedis.tryAcquire(BID, CUST, "s3")).isTrue();

    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    JdbcBannerStore jdbcStore = new JdbcBannerStore(jdbc);
    Banner b = sample();
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    jdbcStore.insert(b);
    jdbcStore.update(b);
    jdbcStore.hardDelete(BID);
    jdbcStore.reorder(List.of(new BannerStore.ReorderItem(BID, 1)), NOW);
    jdbcStore.deactivateExpired(NOW);
    assertThat(jdbcStore.incrementImpressions(BID)).isTrue();
    assertThat(jdbcStore.incrementClicks(BID)).isTrue();
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);
    assertThat(jdbcStore.incrementImpressions(BID)).isFalse();
    assertThat(jdbcStore.findByIds(List.of())).isEmpty();
    assertThat(jdbcStore.findByIds(null)).isEmpty();

    ResultSet rs = Mockito.mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(BID);
    when(rs.getString("headline")).thenReturn("h");
    when(rs.getString("sub_text")).thenReturn("s");
    when(rs.getString("image_url")).thenReturn("https://cdn.test/a.jpg");
    when(rs.getString("placement")).thenReturn("HOME_TOP");
    when(rs.getString("link_type")).thenReturn("COUPON");
    when(rs.getString("link_value")).thenReturn("X");
    when(rs.getString("theme_color")).thenReturn("#fff");
    when(rs.getBoolean("is_live")).thenReturn(true);
    when(rs.getTimestamp("valid_from")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("valid_until")).thenReturn(Timestamp.from(NOW.plusSeconds(1)));
    when(rs.getInt("priority")).thenReturn(1);
    when(rs.getLong("impressions")).thenReturn(0L);
    when(rs.getLong("clicks")).thenReturn(0L);
    when(rs.getObject("created_by")).thenReturn(ADMIN);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
        .thenReturn(1L);
    assertThat(jdbcStore.findById(BID)).isPresent();
    assertThat(jdbcStore.list(BannerPlacement.HOME_TOP, true, 0, 10)).hasSize(1);
    assertThat(jdbcStore.list(null, null, 0, 10)).hasSize(1);
    assertThat(jdbcStore.count(BannerPlacement.HOME_TOP, true)).isEqualTo(1);
    assertThat(jdbcStore.count(null, null)).isEqualTo(1);
    assertThat(jdbcStore.listActiveForPlacement(BannerPlacement.HOME_TOP, NOW)).hasSize(1);
    assertThat(jdbcStore.findByIds(List.of(BID))).hasSize(1);

    when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
        .thenReturn(null);
    assertThat(jdbcStore.count(null, false)).isEqualTo(0);
  }

  private Banner sample() {
    return new Banner(
        BID,
        "Monsoon Sale",
        "sub",
        "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
        BannerPlacement.HOME_TOP,
        BannerLinkType.COUPON,
        "NAMMA25",
        "#1A73E8",
        true,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-12-31T23:59:59Z"),
        1,
        0,
        0,
        ADMIN,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-01T00:00:00Z"));
  }
}
