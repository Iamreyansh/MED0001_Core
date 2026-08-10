package com.nammamedmate.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BannerDomainTest {

  @Test
  void ctrPctAndActiveWindow() {
    Instant from = Instant.parse("2026-07-01T00:00:00Z");
    Instant until = Instant.parse("2026-07-31T23:59:59Z");
    Banner zero =
        new Banner(
            UUID.randomUUID(),
            "h",
            null,
            "https://cdn.test/a.jpg",
            BannerPlacement.HOME_TOP,
            BannerLinkType.COUPON,
            "X",
            null,
            true,
            from,
            until,
            1,
            0,
            10,
            null,
            from,
            from);
    assertThat(zero.ctrPct()).isEqualByComparingTo(BigDecimal.valueOf(0.0).setScale(1));
    assertThat(zero.statusLabel()).isEqualTo("LIVE");

    Banner withCounts =
        new Banner(
            zero.id(),
            zero.headline(),
            zero.subText(),
            zero.imageUrl(),
            zero.placement(),
            zero.linkType(),
            zero.linkValue(),
            zero.themeColor(),
            false,
            from,
            until,
            1,
            128400,
            6420,
            null,
            from,
            from);
    assertThat(withCounts.ctrPct()).isEqualByComparingTo(new BigDecimal("5.0"));
    assertThat(withCounts.statusLabel()).isEqualTo("OFFLINE");
    assertThat(withCounts.activeAt(Instant.parse("2026-07-15T12:00:00Z"))).isFalse();

    Banner live = withCounts;
    Banner liveOn =
        new Banner(
            live.id(),
            live.headline(),
            live.subText(),
            live.imageUrl(),
            live.placement(),
            live.linkType(),
            live.linkValue(),
            live.themeColor(),
            true,
            from,
            until,
            1,
            live.impressions(),
            live.clicks(),
            null,
            from,
            from);
    assertThat(liveOn.activeAt(Instant.parse("2026-07-15T12:00:00Z"))).isTrue();
    assertThat(liveOn.activeAt(Instant.parse("2026-06-01T00:00:00Z"))).isFalse();
    assertThat(liveOn.activeAt(Instant.parse("2026-08-01T00:00:00Z"))).isFalse();
  }
}
