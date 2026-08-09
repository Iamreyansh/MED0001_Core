package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class WalletDomainEnumsTest {

  @Test
  void txType_parseOptional() {
    assertThat(WalletTxType.parseOptional(null)).isNull();
    assertThat(WalletTxType.parseOptional("  ")).isNull();
    assertThat(WalletTxType.parseOptional("credit")).isEqualTo(WalletTxType.CREDIT);
    assertThatThrownBy(() -> WalletTxType.parseOptional("NOPE"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void creditReason_require() {
    assertThat(WalletCreditReason.require("goodwill")).isEqualTo(WalletCreditReason.GOODWILL);
    assertThat(WalletCreditReason.require("ADMIN_CREDIT")).isEqualTo(WalletCreditReason.GOODWILL);
    assertThat(WalletCreditReason.require("CASHBACK")).isEqualTo(WalletCreditReason.PROMOTIONAL);
    assertThatThrownBy(() -> WalletCreditReason.require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(() -> WalletCreditReason.require("   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(() -> WalletCreditReason.require("X"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(() -> WalletCreditReason.require("REFERRAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
  }

  @Test
  void creditReason_requireSystem() {
    assertThat(WalletCreditReason.requireSystem("REFUND")).isEqualTo(WalletCreditReason.REFUND);
    assertThat(WalletCreditReason.requireSystem("ADMIN_CREDIT"))
        .isEqualTo(WalletCreditReason.GOODWILL);
    assertThat(WalletCreditReason.requireSystem("CASHBACK"))
        .isEqualTo(WalletCreditReason.PROMOTIONAL);
    assertThat(WalletCreditReason.requireSystem("GOODWILL")).isEqualTo(WalletCreditReason.GOODWILL);
    assertThat(WalletCreditReason.requireSystem("PROMOTIONAL"))
        .isEqualTo(WalletCreditReason.PROMOTIONAL);
    assertThat(WalletCreditReason.requireSystem("REFERRAL")).isEqualTo(WalletCreditReason.REFERRAL);
    assertThat(WalletCreditReason.requireSystem("REFERRAL_REWARD"))
        .isEqualTo(WalletCreditReason.REFERRAL);
    assertThatThrownBy(() -> WalletCreditReason.requireSystem(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(() -> WalletCreditReason.requireSystem("  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(() -> WalletCreditReason.requireSystem("NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
  }
}
