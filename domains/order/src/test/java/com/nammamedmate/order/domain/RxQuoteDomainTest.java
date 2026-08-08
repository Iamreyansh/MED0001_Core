package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.order.domain.RxQuoteTags.TaggedQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RxQuoteDomainTest {

  @Test
  void pricingIncludesFeesAndFreeDeliveryThreshold() {
    var bill =
        RxQuotePricing.compute(
            List.of(new QuotedMedicine("A", 1, 10_000), new QuotedMedicine("B", 2, 5_000)));
    assertThat(bill.itemTotalPaise()).isEqualTo(15_000);
    assertThat(bill.deliveryFeePaise()).isEqualTo(2_500);
    assertThat(bill.handlingFeePaise()).isEqualTo(500);
    assertThat(bill.totalPayablePaise()).isEqualTo(18_000);

    var free = RxQuotePricing.compute(List.of(new QuotedMedicine("A", 1, 19_900)));
    assertThat(free.deliveryFeePaise()).isZero();
    assertThat(free.totalPayablePaise()).isEqualTo(19_900 + 500);

    var empty = RxQuotePricing.compute(List.of());
    assertThat(empty.itemTotalPaise()).isZero();
    assertThat(empty.deliveryFeePaise()).isZero();
    assertThat(empty.handlingFeePaise()).isZero();
    assertThat(RxQuotePricing.compute(null).totalPayablePaise()).isZero();
  }

  @Test
  void rupeesToPaiseAndQuotedMedicineValidation() {
    assertThat(RxQuotePricing.rupeesToPaise(new BigDecimal("255.00"))).isEqualTo(25_500);
    assertThat(RxQuotePricing.rupeesToPaise("85.5")).isEqualTo(8_550);
    assertThatThrownBy(() -> RxQuotePricing.rupeesToPaise(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuotedMedicine(null, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuotedMedicine(" ", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuotedMedicine("A", 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuotedMedicine("A", 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tagsAndCanViewQuotes() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Map<UUID, List<String>> tags =
        RxQuoteTags.assign(
            List.of(
                new TaggedQuote(a, 22, 27_500, false),
                new TaggedQuote(b, 18, 37_050, false),
                new TaggedQuote(UUID.randomUUID(), 10, 1, true)));
    assertThat(tags.get(a)).containsExactly(RxQuoteTags.LOWEST_PRICE);
    assertThat(tags.get(b)).containsExactly(RxQuoteTags.FASTEST);

    UUID both = UUID.randomUUID();
    Map<UUID, List<String>> dual =
        RxQuoteTags.assign(List.of(new TaggedQuote(both, 10, 100, false)));
    assertThat(dual.get(both)).containsExactly(RxQuoteTags.FASTEST, RxQuoteTags.LOWEST_PRICE);

    Instant t0 = Instant.parse("2026-08-08T10:00:00Z");
    assertThat(RxQuoteTags.canViewQuotes(0, t0, t0.plusSeconds(180))).isFalse();
    assertThat(RxQuoteTags.canViewQuotes(1, t0, t0.plusSeconds(180))).isFalse();
    assertThat(RxQuoteTags.canViewQuotes(1, t0, t0.plusSeconds(360))).isTrue();
    assertThat(RxQuoteTags.canViewQuotes(2, t0, t0.plusSeconds(60))).isTrue();
    assertThat(RxQuoteTags.canViewQuotes(1, null, t0)).isFalse();
    assertThat(RxQuoteTags.canViewQuotes(1, t0, null)).isFalse();
    assertThat(RxQuoteTags.assign(List.of()).isEmpty()).isTrue();
    assertThat(RxQuoteTags.assign(List.of(new TaggedQuote(UUID.randomUUID(), 1, 1, true))).values())
        .allSatisfy(tagList -> assertThat(tagList).isEmpty());
  }

  @Test
  void broadcastPharmacyQuoteExpiry() {
    Instant now = Instant.parse("2026-08-08T10:00:00Z");
    RxBroadcastPharmacy slot =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1.2,
            RxPharmacySlotStatus.QUOTED,
            List.of(new QuotedMedicine("A", 1, 100)),
            20,
            3100L,
            now,
            now.plusSeconds(900),
            now,
            now.plusSeconds(1200),
            List.of());
    assertThat(slot.quoteExpired(now.plusSeconds(1199))).isFalse();
    assertThat(slot.quoteExpired(now.plusSeconds(1200))).isTrue();
    RxBroadcastPharmacy open =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1.0,
            RxPharmacySlotStatus.NOTIFIED,
            null,
            null,
            null,
            now,
            now.plusSeconds(900),
            null,
            null,
            null);
    assertThat(open.quoteExpired(now)).isFalse();
    assertThat(
            new RxBroadcast(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Ravi",
                    null,
                    null,
                    RxBroadcastStatus.ACTIVE,
                    0,
                    now,
                    now.plusSeconds(1),
                    null,
                    null,
                    now)
                .medicinesRequested())
        .isEmpty();
  }
}
