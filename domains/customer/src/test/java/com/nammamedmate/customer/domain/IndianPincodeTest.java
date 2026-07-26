package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IndianPincodeTest {

  @Test
  void requireValid_acceptsSixDigits() {
    assertThat(IndianPincode.requireValid("560066")).isEqualTo("560066");
    assertThat(IndianPincode.requireValid(" 110001 ")).isEqualTo("110001");
  }

  @Test
  void requireValid_rejectsInvalid() {
    assertThatThrownBy(() -> IndianPincode.requireValid("56006"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pincode must be exactly 6 digits");
    assertThatThrownBy(() -> IndianPincode.requireValid("056006"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IndianPincode.requireValid(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IndianPincode.requireValid("56A066"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
