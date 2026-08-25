package com.nammamedmate.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.out.geocode.CachingGeocodePort;
import com.nammamedmate.customer.adapter.out.geocode.GoogleMapsGeocodeClient;
import com.nammamedmate.customer.adapter.out.geocode.StubGeocodeClient;
import com.nammamedmate.customer.adapter.out.razorpay.RazorpayVpaClient;
import com.nammamedmate.customer.adapter.out.razorpay.StubRazorpayVpaClient;
import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.CustomerOrderHistoryPort;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.customer.application.port.out.LoyaltyCartPort;
import com.nammamedmate.customer.application.port.out.PaymentMethodInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.RazorpayVpaPort;
import com.nammamedmate.customer.application.port.out.WalletCreditLimitPort;
import com.nammamedmate.kernel.error.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CustomerConfig {

  @Bean
  @ConditionalOnMissingBean(WalletCreditLimitPort.class)
  WalletCreditLimitPort defaultWalletCreditLimit(
      @Value("${medmate.wallet.max-credit-paise:100000}") long maxCreditPaise) {
    return () -> maxCreditPaise;
  }

  @Bean
  @ConditionalOnMissingBean(LoyaltyCartPort.class)
  LoyaltyCartPort stubLoyaltyCartPort() {
    return (customerId, cartId) -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean(CustomerOrderHistoryPort.class)
  CustomerOrderHistoryPort profileOrderHistoryPort(CustomerProfileStore profiles) {
    // Fail-closed first-order gate off customers.total_orders until EPIC-010 wires a live order
    // port. A customer with any recorded order can no longer apply a referral code.
    return customerId ->
        profiles.findById(customerId).map(CustomerProfileRecord::totalOrders).orElse(0) > 0;
  }

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
  @ConditionalOnMissingBean(PaymentMethodInActiveOrderPort.class)
  PaymentMethodInActiveOrderPort noPaymentMethodInActiveOrderPort() {
    // ponytail: orders domain (EPIC-010) not wired — payment-method delete never blocked
    return methodId -> false;
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

  @Bean
  @ConditionalOnMissingBean(RazorpayVpaPort.class)
  RazorpayVpaPort razorpayVpaPort(
      ObjectMapper objectMapper,
      @Value("${medmate.razorpay.key-id:}") String keyId,
      @Value("${medmate.razorpay.key-secret:}") String keySecret) {
    if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
      return new StubRazorpayVpaClient();
    }
    return new RazorpayVpaClient(keyId, keySecret, objectMapper, CustomerConfig::razorpayHttpGet);
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

  /** Razorpay VPA HTTP — 5s timeout per story; JaCoCo excludes Config. */
  static String razorpayHttpGet(RazorpayVpaClient.Request request) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(5)).GET();
      for (Map.Entry<String, String> header : request.headers().entrySet()) {
        builder.header(header.getKey(), header.getValue());
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException("VPA_VALIDATION_FAILED", "Razorpay VPA validation unavailable", 503);
      }
      return response.body();
    } catch (AppException ex) {
      throw ex;
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AppException("VPA_VALIDATION_TIMEOUT", "Razorpay VPA validation timed out", 503);
    }
  }
}
