package com.nammamedmate.crm.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import org.junit.jupiter.api.Test;

class FailClosedSubscriptionPaymentAdapterTest {

  @Test
  void paidChargeIsDisabled() {
    FailClosedSubscriptionPaymentAdapter adapter = new FailClosedSubscriptionPaymentAdapter();
    assertThatThrownBy(() -> adapter.charge(Ids.newId(), 100, "paid", "k"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_PAYMENTS_DISABLED");
    assertThatThrownBy(() -> adapter.charge(Ids.newId(), 0, "free", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_FAILED");
  }
}
