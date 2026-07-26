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
    assertThatThrownBy(() -> WalletCreditReason.require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> WalletCreditReason.require("   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> WalletCreditReason.require("X"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
