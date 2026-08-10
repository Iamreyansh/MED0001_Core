package com.nammamedmate.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignDomainTest {

  @Test
  void roiOpenCtrAndAttributionWindow() {
    Instant click = Instant.parse("2026-07-24T10:00:00Z");
    assertThat(Campaign.isAttributable(click, click.plusSeconds(24 * 3600))).isTrue();
    assertThat(Campaign.isAttributable(click, click.plus(Campaign.ATTRIBUTION_WINDOW))).isTrue();
    assertThat(Campaign.isAttributable(click, click.plusSeconds(49 * 3600))).isFalse();
    assertThat(Campaign.isAttributable(click, click.minusSeconds(1))).isFalse();
    assertThat(Campaign.isAttributable(null, click)).isFalse();
    assertThat(Campaign.isAttributable(click, null)).isFalse();

    // AC-8: revenue 186000 Rs = 18600000 paise, cost 25800 Rs = 2580000 paise → ~620.9
    assertThat(Campaign.roiPct(18_600_000L, 2_580_000L))
        .isEqualByComparingTo(new BigDecimal("620.9"));
    assertThat(Campaign.roiPct(100, 0)).isEqualByComparingTo(new BigDecimal("0.0"));

    Campaign c =
        new Campaign(
            UUID.randomUUID(),
            "x",
            CampaignChannel.WHATSAPP,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            2_580_000L,
            12400,
            12185,
            4655,
            1054,
            620,
            18_600_000L,
            12400,
            CampaignStatus.COMPLETED,
            null,
            Instant.now(),
            Instant.now());
    assertThat(c.openRatePct()).isEqualByComparingTo(new BigDecimal("38.2"));
    assertThat(c.ctrPct()).isEqualByComparingTo(new BigDecimal("8.6"));
    assertThat(c.roiPct()).isEqualByComparingTo(new BigDecimal("620.9"));
    assertThat(c.isImmutable()).isTrue();

    Campaign draft =
        new Campaign(
            UUID.randomUUID(),
            "d",
            CampaignChannel.PUSH,
            UUID.randomUUID(),
            null,
            "hi",
            "body",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            CampaignStatus.DRAFT,
            null,
            Instant.now(),
            Instant.now());
    assertThat(draft.openRatePct()).isEqualByComparingTo(new BigDecimal("0.0"));
    assertThat(draft.ctrPct()).isEqualByComparingTo(new BigDecimal("0.0"));
    assertThat(draft.isImmutable()).isFalse();
    Campaign running =
        new Campaign(
            UUID.randomUUID(),
            "r",
            CampaignChannel.EMAIL,
            UUID.randomUUID(),
            null,
            "s",
            "b",
            null,
            null,
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            CampaignStatus.RUNNING,
            null,
            Instant.now(),
            Instant.now());
    assertThat(running.isImmutable()).isTrue();
  }
}
