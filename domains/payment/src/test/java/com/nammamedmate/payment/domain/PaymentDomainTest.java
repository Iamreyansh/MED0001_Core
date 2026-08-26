package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDomainTest {

  @Test
  void methodHelpers() {
    assertThat(PaymentMethod.CARD.isOnline()).isTrue();
    assertThat(PaymentMethod.WALLET_ONLY.isOnline()).isFalse();
    assertThat(PaymentMethod.UPI.isOnline()).isTrue();
    assertThat(PaymentMethod.COD.isOnline()).isFalse();
    assertThat(PaymentMethod.fromOrderMethod("wallet")).isEqualTo(PaymentMethod.WALLET_ONLY);
    assertThat(PaymentMethod.fromOrderMethod("UPI")).isEqualTo(PaymentMethod.UPI);
    assertThatThrownBy(() -> PaymentMethod.fromOrderMethod(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PaymentMethod.fromOrderMethod(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void moneyFormats() {
    assertThat(MoneyFormats.paiseToRupees(49500)).isEqualByComparingTo("495.00");
    assertThat(MoneyFormats.paiseToRupees(1)).isEqualByComparingTo("0.01");
    assertThat(MoneyFormats.parsePositiveRupeesToPaise("12.50")).isEqualTo(1250L);
    assertThat(MoneyFormats.parsePositiveRupeesToPaise(new java.math.BigDecimal("1.00")))
        .isEqualTo(100L);
    assertThat(MoneyFormats.parsePositiveRupeesToPaise(2)).isEqualTo(200L);
    assertThatThrownBy(() -> MoneyFormats.parsePositiveRupeesToPaise(null))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> MoneyFormats.parsePositiveRupeesToPaise(0))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> MoneyFormats.parsePositiveRupeesToPaise("abc"))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> MoneyFormats.parsePositiveRupeesToPaise(java.util.Map.of()))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(
            () -> MoneyFormats.parsePositiveRupeesToPaise(new java.math.BigDecimal("1.001")))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
  }

  @Test
  void paymentLifecycle() {
    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    Payment p =
        new Payment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            100,
            0,
            100,
            null,
            PaymentMethod.UPI,
            PaymentStatus.PENDING,
            "order_1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    assertThat(p.currency()).isEqualTo("INR");
    assertThat(p.webhookEvents()).isEmpty();
    p.appendWebhookEvent(" ");
    p.appendWebhookEvent("payment.captured");
    assertThat(p.webhookEvents()).containsExactly("payment.captured");
    p.capture("pay_1", "sig", 2L, "{}", now);
    assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
    p.fail("pay_2", "bank", "{}", now.plusSeconds(1));
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(p.gatewayPaymentId()).isEqualTo("pay_2");
    p.fail(null, "again", null, now.plusSeconds(2));
    assertThat(p.gatewayPaymentId()).isEqualTo("pay_2");
    p.fail("  ", "blank", null, now.plusSeconds(3));
    assertThat(p.gatewayPaymentId()).isEqualTo("pay_2");
    Payment withBlankCurrency =
        new Payment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            0,
            1,
            "  ",
            PaymentMethod.CARD,
            PaymentStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            now,
            now);
    assertThat(withBlankCurrency.currency()).isEqualTo("INR");
    p.appendWebhookEvent(null);
    p.fail("  ", "blank-id", null, now.plusSeconds(4));
    p.touch(now.plusSeconds(5));
    assertThat(p.updatedAt()).isEqualTo(now.plusSeconds(5));
  }
}
