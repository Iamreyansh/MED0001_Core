package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefundStatusesTest {

  @Test
  void mappingsAndMessages() {
    assertThat(RefundStatuses.toApiStatus("PENDING")).isEqualTo("PENDING");
    assertThat(RefundStatuses.toApiStatus("INITIATED")).isEqualTo("PROCESSING");
    assertThat(RefundStatuses.toApiStatus("PROCESSED")).isEqualTo("COMPLETED");
    assertThat(RefundStatuses.toApiStatus("FAILED")).isEqualTo("FAILED");
    assertThat(RefundStatuses.toApiStatus("OTHER")).isEqualTo("OTHER");
    assertThat(RefundStatuses.toStorageStatusFilter("PENDING")).isEqualTo("PENDING");
    assertThat(RefundStatuses.toStorageStatusFilter("PROCESSING")).isEqualTo("INITIATED");
    assertThat(RefundStatuses.toStorageStatusFilter(" ")).isNull();
    assertThatThrownBy(() -> RefundStatuses.toStorageStatusFilter("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(RefundStatuses.toApiRefundTo("SOURCE")).isEqualTo("SOURCE_ACCOUNT");
    assertThat(RefundStatuses.toApiRefundTo("SOURCE_ACCOUNT")).isEqualTo("SOURCE_ACCOUNT");
    assertThat(RefundStatuses.toApiRefundTo("WALLET")).isEqualTo("WALLET");
    assertThat(RefundStatuses.toApiRefundTo("X")).isEqualTo("X");
    assertThat(RefundStatuses.toStorageRefundToFilter("SOURCE_ACCOUNT")).isEqualTo("SOURCE");
    assertThat(RefundStatuses.toStorageRefundToFilter("SOURCE")).isEqualTo("SOURCE");
    assertThat(RefundStatuses.toStorageRefundToFilter(null)).isNull();
    assertThatThrownBy(() -> RefundStatuses.toStorageRefundToFilter("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(RefundStatuses.customerMessage("COMPLETED", "WALLET")).contains("wallet");
    assertThat(RefundStatuses.customerMessage("COMPLETED", "SOURCE_ACCOUNT")).contains("original");
    assertThat(RefundStatuses.customerMessage("PENDING", "WALLET")).isNotBlank();
    assertThat(RefundStatuses.toStorageStatusFilter("FAILED")).isEqualTo("FAILED");
    assertThat(RefundStatuses.toStorageRefundToFilter(" ")).isNull();
    assertThat(RefundStatuses.toStorageRefundToFilter("WALLET")).isEqualTo("WALLET");
    assertThat(RefundStatuses.AUTO_REFUND_MAX_PAISE).isEqualTo(50_000L);
  }
}
