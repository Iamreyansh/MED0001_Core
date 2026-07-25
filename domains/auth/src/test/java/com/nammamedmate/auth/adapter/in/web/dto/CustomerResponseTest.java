package com.nammamedmate.auth.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.application.VerifyOtpResult;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerResponseTest {

  @Test
  void mapsWalletPaiseToRupees() {
    CustomerRecord customer =
        new CustomerRecord(
            Ids.newId(),
            "+919876543210",
            List.of(),
            "Ramesh",
            null,
            null,
            "MALE",
            "kn",
            "LOYAL",
            12550L,
            38,
            Instant.parse("2025-01-10T06:30:00Z"));
    CustomerResponse response = CustomerResponse.from(customer);
    assertThat(response.walletBalance()).isEqualByComparingTo(new BigDecimal("125.50"));
    assertThat(response.loyaltyPoints()).isEqualTo(38);

    VerifyOtpResponse verify =
        VerifyOtpResponse.from(new VerifyOtpResult("a", "r", "Bearer", 900, 1, false, customer));
    assertThat(verify.isNewUser()).isFalse();
    assertThat(verify.customer().name()).isEqualTo("Ramesh");
  }
}
