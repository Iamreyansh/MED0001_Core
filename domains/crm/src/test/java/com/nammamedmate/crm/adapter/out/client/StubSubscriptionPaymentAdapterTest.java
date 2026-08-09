package com.nammamedmate.crm.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubSubscriptionPaymentAdapterTest {

  @Test
  void succeedsAndCanFail() {
    StubSubscriptionPaymentAdapter stub = new StubSubscriptionPaymentAdapter();
    UUID account = Ids.newId();
    assertThat(stub.charge(account, 100, "x", "k1")).isNotNull();
    assertThat(stub.charge(null, 0, "x", null)).isNotNull();
    stub.failNextFor(account);
    assertThatThrownBy(() -> stub.charge(account, 100, "x", "k2"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_FAILED");
    assertThatThrownBy(() -> stub.charge(account, -1, "x", "k3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_FAILED");
    stub.clearFailures();
  }

  @Test
  void sameIdempotencyKeyReplaysPaymentId() {
    StubSubscriptionPaymentAdapter stub = new StubSubscriptionPaymentAdapter();
    UUID account = Ids.newId();
    UUID first = stub.charge(account, 100, "d", "same-key");
    UUID second = stub.charge(account, 100, "d", "same-key");
    assertThat(second).isEqualTo(first);
    assertThat(stub.charge(account, 50, "d", "  ")).isNotNull();
    assertThat(stub.charge(account, 50, "d", "")).isNotNull();
  }
}
