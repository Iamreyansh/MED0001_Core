package com.nammamedmate.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.out.geocode.CachingGeocodePort;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class CustomerConfigTest {

  @Test
  void noActiveOrdersPort_returnsFalse() {
    CustomerConfig config = new CustomerConfig();
    assertThat(config.noActiveOrdersPort().hasActiveOrders(UUID.randomUUID())).isFalse();
  }

  @Test
  void noAddressInActiveOrderPort_returnsFalse() {
    CustomerConfig config = new CustomerConfig();
    assertThat(config.noAddressInActiveOrderPort().isAddressInActiveOrder(UUID.randomUUID()))
        .isFalse();
  }

  @Test
  void geocodePort_withoutApiKey_usesStubBehindCache() {
    CustomerConfig config = new CustomerConfig();
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
    when(redis.getIfAvailable()).thenReturn(null);

    GeocodePort port = config.geocodePort(redis, new ObjectMapper(), "");

    assertThat(port).isInstanceOf(CachingGeocodePort.class);
    assertThat(port.reverseGeocode(12.9716, 77.5946).city()).isEqualTo("Bengaluru");
  }

  @Test
  void geocodePort_withApiKey_wrapsGoogleClient() {
    CustomerConfig config = new CustomerConfig();
    GeocodePort port = config.geocodePort(null, new ObjectMapper(), "test-key");

    assertThat(port).isInstanceOf(CachingGeocodePort.class);
  }

  @Test
  void geocodePort_blankApiKey_usesStub() {
    CustomerConfig config = new CustomerConfig();
    GeocodePort port = config.geocodePort(null, new ObjectMapper(), "   ");

    assertThat(port.reverseGeocode(12.9716, 77.5946).city()).isEqualTo("Bengaluru");
  }
}
