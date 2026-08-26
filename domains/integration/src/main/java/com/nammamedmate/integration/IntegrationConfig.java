package com.nammamedmate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.LiveCashfreeClient;
import com.nammamedmate.integration.adapter.out.client.LiveCashfreePayoutClient;
import com.nammamedmate.integration.adapter.out.client.LiveMapsClient;
import com.nammamedmate.integration.adapter.out.client.StubCashfreeClient;
import com.nammamedmate.integration.adapter.out.client.StubCashfreePayoutClient;
import com.nammamedmate.integration.adapter.out.client.StubMapsClient;
import com.nammamedmate.integration.application.port.out.CashfreeClientPort;
import com.nammamedmate.integration.application.port.out.CashfreePayoutClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntegrationConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock integrationClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(CashfreeClientPort.class)
  CashfreeClientPort cashfreeClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.cashfree.app-id:}") String appId,
      @Value("${medmate.cashfree.secret-key:}") String secretKey,
      @Value("${medmate.cashfree.webhook-secret:}") String webhookSecret,
      @Value("${medmate.cashfree.mode:TEST}") String mode) {
    if (blank(appId) || blank(secretKey)) {
      return new StubCashfreeClient(webhookSecret);
    }
    if (blank(webhookSecret)) {
      throw new IllegalStateException(
          "medmate.cashfree.webhook-secret required when live Cashfree keys are set");
    }
    return new LiveCashfreeClient(
        appId, secretKey, webhookSecret, mode, objectMapper, IntegrationConfig::httpPost);
  }

  @Bean
  @ConditionalOnMissingBean(CashfreePayoutClientPort.class)
  CashfreePayoutClientPort cashfreePayoutClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.cashfree.payouts-client-id:}") String clientId,
      @Value("${medmate.cashfree.payouts-client-secret:}") String clientSecret) {
    if (blank(clientId) || blank(clientSecret)) {
      return new StubCashfreePayoutClient();
    }
    return new LiveCashfreePayoutClient(
        clientId, clientSecret, objectMapper, IntegrationConfig::httpPostX);
  }

  @Bean
  @ConditionalOnMissingBean(MapsClientPort.class)
  MapsClientPort mapsClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.maps.geocode.api-key:}") String geocodeKey,
      @Value("${medmate.maps.distance-matrix.api-key:}") String distanceMatrixKey,
      @Value("${medmate.maps.directions.api-key:}") String directionsKey) {
    if (blank(geocodeKey) && blank(distanceMatrixKey) && blank(directionsKey)) {
      return new StubMapsClient();
    }
    return new LiveMapsClient(
        geocodeKey, distanceMatrixKey, directionsKey, objectMapper, IntegrationConfig::httpGet);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  static String httpGet(URI uri) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "MAPS_API_UNAVAILABLE",
            "HTTP " + response.statusCode() + ": " + truncate(response.body()),
            503);
      }
      return response.body();
    } catch (AppException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps HTTP call failed", 503);
    }
  }

  static String httpPost(LiveCashfreeClient.Request request) {
    return exchange(request.uri(), request.headers(), request.body());
  }

  static String httpPostX(LiveCashfreePayoutClient.Request request) {
    return exchange(request.uri(), request.headers(), request.body());
  }

  private static String exchange(java.net.URI uri, Map<String, String> headers, String body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(20))
              .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
      headers.forEach(builder::header);
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "CASHFREE_UNAVAILABLE",
            "HTTP " + response.statusCode() + ": " + truncate(response.body()),
            503);
      }
      return response.body();
    } catch (AppException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree HTTP call failed", 503);
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200);
  }
}
