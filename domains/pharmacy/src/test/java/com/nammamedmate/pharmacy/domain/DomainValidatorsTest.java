package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DomainValidatorsTest {

  @Test
  void gstinValidAndChecksum() {
    assertThat(Gstin.requireValid("29AABPP1234F1ZZ")).isEqualTo("29AABPP1234F1ZZ");
    assertThat(Gstin.stateCode("29AABPP1234F1ZZ")).isEqualTo("29");
  }

  @Test
  void gstinRejectsBadChecksumAndFormat() {
    assertThatThrownBy(() -> Gstin.requireValid("29AABPP1234F1ZA"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("99AABPP1234F1ZZ")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("short")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid(null)).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("29AABPP1234F1YZ")).hasMessage("INVALID_GSTIN");
  }

  @Test
  void panAndPhoneAndPassword() {
    assertThat(Pan.requireValid("AABPP1234F")).isEqualTo("AABPP1234F");
    assertThatThrownBy(() -> Pan.requireValid("AAAXP1234F")).hasMessage("INVALID_PAN");
    assertThat(IndianPhone.requireValid("+919876543210")).isEqualTo("+919876543210");
    assertThatThrownBy(() -> IndianPhone.requireValid("+911876543210")).hasMessage("INVALID_PHONE");
    assertThat(PharmacyPassword.requireValid("Passw0rd!")).isEqualTo("Passw0rd!");
    assertThatThrownBy(() -> PharmacyPassword.requireValid("password"))
        .hasMessage("INVALID_PASSWORD_STRENGTH");
  }

  @Test
  void businessTypeStateMagic() {
    assertThat(BusinessTypes.requireValid("pharmacy")).isEqualTo("PHARMACY");
    assertThatThrownBy(() -> BusinessTypes.requireValid("SHOP"))
        .hasMessage("INVALID_BUSINESS_TYPE");
    assertThat(IndianStates.requireValid("karnataka")).isEqualTo("Karnataka");
    assertThatThrownBy(() -> IndianStates.requireValid("Narnia")).hasMessage("INVALID_STATE");
    assertThat(MagicRegistrationOtp.isMagicEmail("a@nammamedmate.test")).isTrue();
    assertThat(MagicRegistrationOtp.matches("a@nammamedmate.test", "123456")).isTrue();
    assertThat(MagicRegistrationOtp.matches("a@other.com", "123456")).isFalse();
  }
}
