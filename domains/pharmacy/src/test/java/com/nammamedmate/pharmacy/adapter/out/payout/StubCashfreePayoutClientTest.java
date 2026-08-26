package com.nammamedmate.pharmacy.adapter.out.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.CashfreePayoutPort.PayoutRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubCashfreePayoutClientTest {

  @Test
  void initiatePayout_returnsStubId() {
    StubCashfreePayoutClient client = new StubCashfreePayoutClient();
    var result =
        client.initiatePayout(
            new PayoutRequest(Ids.newId(), UUID.randomUUID(), 10_000L, "4321", "HDFC0001234"));
    assertThat(result.cashfreeTransferId()).startsWith("pout_stub_");
    assertThat(result.estimatedCreditHours()).isEqualTo(4);
  }
}
