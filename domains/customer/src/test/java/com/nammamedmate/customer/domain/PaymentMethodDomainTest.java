package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class PaymentMethodDomainTest {

  @Test
  void upiVpa_validAndMask() {
    assertThat(UpiVpa.requireValid("Ramesh@okaxis")).isEqualTo("ramesh@okaxis");
    assertThat(UpiVpa.maskHandle("ramesh@okaxis")).isEqualTo("***@okaxis");
    assertThat(UpiVpa.maskHandle("noat")).isEqualTo("***");
  }

  @Test
  void upiVpa_invalid() {
    assertThatThrownBy(() -> UpiVpa.requireValid(null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> UpiVpa.requireValid("   ")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> UpiVpa.requireValid("x")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> UpiVpa.requireValid("x".repeat(101))).isInstanceOf(AppException.class);
  }

  @Test
  void cardNetwork_parse() {
    assertThat(CardNetwork.parse("visa")).isEqualTo(CardNetwork.VISA);
    assertThatThrownBy(() -> CardNetwork.parse(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> CardNetwork.parse("  ")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> CardNetwork.parse("DISCOVER"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).getMessage())
        .asString()
        .contains("card_network");
  }

  @Test
  void cardType_parse() {
    assertThat(CardType.parse("debit")).isEqualTo(CardType.DEBIT);
    assertThatThrownBy(() -> CardType.parse("")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> CardType.parse(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> CardType.parse("CHARGE")).isInstanceOf(AppException.class);
  }

  @Test
  void paymentMethodType_values() {
    assertThat(PaymentMethodType.UPI.name()).isEqualTo("UPI");
    assertThat(PaymentMethodType.CARD.name()).isEqualTo("CARD");
  }
}
