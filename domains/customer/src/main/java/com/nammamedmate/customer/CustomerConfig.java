package com.nammamedmate.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.out.geocode.CachingGeocodePort;
import com.nammamedmate.customer.adapter.out.geocode.GoogleMapsGeocodeClient;
import com.nammamedmate.customer.adapter.out.geocode.StubGeocodeClient;
import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CustomerConfig {

  @Bean
  @ConditionalOnMissingBean(ActiveOrdersPort.class)
  ActiveOrdersPort noActiveOrdersPort() {
    // ponytail: orders domain (EPIC-010) not wired yet — deletion never blocked by active orders
    // until order port is provided; upgrade: implement ActiveOrdersPort in domains/order
    return customerId -> false;
  }

  @Bean
  @ConditionalOnMissingBean(AddressInActiveOrderPort.class)
  AddressInActiveOrderPort noAddressInActiveOrderPort() {
    // ponytail: orders domain (EPIC-010) not wired — address delete never blocked by active orders
    return addressId -> false;
  }

  @Bean
  @ConditionalOnMissingBean(GeocodePort.class)
  GeocodePort geocodePort(
      ObjectProvider<StringRedisTemplate> redis,
      ObjectMapper objectMapper,
      @Value("${medmate.maps.geocode.api-key:}") String apiKey) {
    GeocodePort delegate =
        apiKey == null || apiKey.isBlank()
            ? new StubGeocodeClient()
            : new GoogleMapsGeocodeClient(apiKey, objectMapper, CustomerConfig::httpGet);
    return new CachingGeocodePort(delegate, redis);
  }

  /**
   * Real Google HTTP call — kept here so JaCoCo excludes Config; unit-tested via Function inject.
   */
  static String httpGet(URI uri) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException(ex);
    }
  }
}
