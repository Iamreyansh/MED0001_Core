package com.nammamedmate.customer.adapter.out.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.customer.application.port.out.GeocodePort.SuggestedAddress;
import org.junit.jupiter.api.Test;

class StubGeocodeClientTest {

  private final StubGeocodeClient client = new StubGeocodeClient();

  @Test
  void bengaluruPin_returnsCityAndPincode() {
    SuggestedAddress address = client.reverseGeocode(12.9716, 77.5946);

    assertThat(address.city()).isEqualTo("Bengaluru");
    assertThat(address.pincode()).isEqualTo("560001");
    assertThat(address.areaLocality()).isEqualTo("MG Road");
  }

  @Test
  void otherCoords_returnsGeneric() {
    SuggestedAddress address = client.reverseGeocode(19.0760, 72.8777);

    assertThat(address.city()).isEqualTo("City");
    assertThat(address.pincode()).isNotBlank();
  }

  @Test
  void nearLatButWrongLng_notBengaluru() {
    SuggestedAddress address = client.reverseGeocode(12.9716, 70.0);
    assertThat(address.city()).isEqualTo("City");
  }
}
