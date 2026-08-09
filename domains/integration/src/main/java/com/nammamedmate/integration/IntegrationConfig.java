package com.nammamedmate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.LiveDigiLockerClient;
import com.nammamedmate.integration.adapter.out.client.LiveDrugRegistryClient;
import com.nammamedmate.integration.adapter.out.client.LiveFssaiClient;
import com.nammamedmate.integration.adapter.out.client.LiveGspClient;
import com.nammamedmate.integration.adapter.out.client.LiveGstnClient;
import com.nammamedmate.integration.adapter.out.client.LiveMapsClient;
import com.nammamedmate.integration.adapter.out.client.LiveRazorpayClient;
import com.nammamedmate.integration.adapter.out.client.LiveRazorpayXClient;
import com.nammamedmate.integration.adapter.out.client.LiveZohoBooksClient;
import com.nammamedmate.integration.adapter.out.client.StubCommunicationProvider;
import com.nammamedmate.integration.adapter.out.client.StubDigiLockerClient;
import com.nammamedmate.integration.adapter.out.client.StubDrugRegistryClient;
import com.nammamedmate.integration.adapter.out.client.StubFssaiClient;
import com.nammamedmate.integration.adapter.out.client.StubGspClient;
import com.nammamedmate.integration.adapter.out.client.StubGstnClient;
import com.nammamedmate.integration.adapter.out.client.StubMapsClient;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayClient;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayXClient;
import com.nammamedmate.integration.adapter.out.client.StubZohoBooksClient;
import com.nammamedmate.integration.adapter.out.persistence.LocalAccountingExportObjectStore;
import com.nammamedmate.integration.application.port.out.AccountingDataPort;
import com.nammamedmate.integration.application.port.out.AccountingExportObjectStore;
import com.nammamedmate.integration.application.port.out.AccountingPlanPort;
import com.nammamedmate.integration.application.port.out.CommunicationProviderPort;
import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import com.nammamedmate.integration.application.port.out.DrugRegistryClientPort;
import com.nammamedmate.integration.application.port.out.FssaiClientPort;
import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.integration.application.port.out.ZohoBooksClientPort;
import com.nammamedmate.integration.domain.AccountingVoucher;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AesGcmCipher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
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
  @ConditionalOnMissingBean(RazorpayClientPort.class)
  RazorpayClientPort razorpayClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.razorpay.key-id:}") String keyId,
      @Value("${medmate.razorpay.key-secret:}") String keySecret,
      @Value("${medmate.razorpay.webhook-secret:}") String webhookSecret,
      @Value("${medmate.razorpay.mode:TEST}") String mode) {
    if (blank(keyId) || blank(keySecret)) {
      return new StubRazorpayClient(webhookSecret);
    }
    if (blank(webhookSecret)) {
      throw new IllegalStateException(
          "medmate.razorpay.webhook-secret required when live Razorpay keys are set");
    }
    return new LiveRazorpayClient(
        keyId, keySecret, webhookSecret, mode, objectMapper, IntegrationConfig::httpPost);
  }

  @Bean
  @ConditionalOnMissingBean(RazorpayXClientPort.class)
  RazorpayXClientPort razorpayXClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.razorpayx.key-id:}") String keyId,
      @Value("${medmate.razorpayx.key-secret:}") String keySecret) {
    if (blank(keyId) || blank(keySecret)) {
      return new StubRazorpayXClient();
    }
    return new LiveRazorpayXClient(keyId, keySecret, objectMapper, IntegrationConfig::httpPostX);
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

  @Bean
  @ConditionalOnMissingBean(GstnClientPort.class)
  GstnClientPort gstnClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.gstn.api-key:}") String apiKey,
      @Value("${medmate.gstn.base-url:https://gstn.example.invalid/v1}") String baseUrl) {
    if (blank(apiKey)) {
      return new StubGstnClient();
    }
    return new LiveGstnClient(apiKey, baseUrl, objectMapper, IntegrationConfig::httpGetGov);
  }

  @Bean
  @ConditionalOnMissingBean(DrugRegistryClientPort.class)
  DrugRegistryClientPort drugRegistryClientPort(
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${medmate.drug-registry.api-key:}") String apiKey,
      @Value("${medmate.drug-registry.base-url:https://drug-registry.example.invalid/v1}")
          String baseUrl) {
    if (blank(apiKey)) {
      return new StubDrugRegistryClient(clock);
    }
    return new LiveDrugRegistryClient(apiKey, baseUrl, objectMapper, IntegrationConfig::httpGetGov);
  }

  @Bean
  @ConditionalOnMissingBean(FssaiClientPort.class)
  FssaiClientPort fssaiClientPort(
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${medmate.fssai.api-key:}") String apiKey,
      @Value("${medmate.fssai.base-url:https://fssai.example.invalid/v1}") String baseUrl) {
    if (blank(apiKey)) {
      return new StubFssaiClient(clock);
    }
    return new LiveFssaiClient(apiKey, baseUrl, objectMapper, IntegrationConfig::httpGetGov);
  }

  @Bean
  @ConditionalOnMissingBean(GspClientPort.class)
  GspClientPort gspClientPort(
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${medmate.gsp.client-id:}") String clientId,
      @Value("${medmate.gsp.client-secret:}") String clientSecret,
      @Value("${medmate.gsp.base-url:https://gsp.example.invalid/v1}") String baseUrl) {
    if (blank(clientId) || blank(clientSecret)) {
      return new StubGspClient(clock);
    }
    return new LiveGspClient(
        clientId, clientSecret, baseUrl, objectMapper, IntegrationConfig::httpGsp);
  }

  @Bean
  @ConditionalOnMissingBean(DigiLockerClientPort.class)
  DigiLockerClientPort digiLockerClientPort(
      ObjectMapper objectMapper,
      @Value("${medmate.digilocker.client-id:}") String clientId,
      @Value("${medmate.digilocker.client-secret:}") String clientSecret,
      @Value(
              "${medmate.digilocker.authorize-url:https://api.digitallocker.gov.in/public/oauth2/1/authorize}")
          String authorizeUrl,
      @Value(
              "${medmate.digilocker.token-url:https://api.digitallocker.gov.in/public/oauth2/1/token}")
          String tokenUrl) {
    if (blank(clientId) || blank(clientSecret)) {
      return new StubDigiLockerClient(clientId, authorizeUrl);
    }
    return new LiveDigiLockerClient(
        clientId,
        clientSecret,
        authorizeUrl,
        tokenUrl,
        objectMapper,
        IntegrationConfig::httpPostDigiLocker);
  }

  @Bean
  @ConditionalOnMissingBean(CommunicationProviderPort.class)
  CommunicationProviderPort communicationProviderPort() {
    // Live provider clients ship with EPIC-017 delivery; control plane uses stub until then.
    return new StubCommunicationProvider();
  }

  @Bean
  @ConditionalOnMissingBean(ZohoBooksClientPort.class)
  ZohoBooksClientPort zohoBooksClientPort(
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${medmate.zoho.client-id:}") String clientId,
      @Value("${medmate.zoho.client-secret:}") String clientSecret,
      @Value("${medmate.zoho.accounts-base-url:https://accounts.zoho.in}") String accountsBaseUrl,
      @Value("${medmate.zoho.books-base-url:https://www.zohoapis.in/books/v3}")
          String booksBaseUrl) {
    if (blank(clientId) || blank(clientSecret)) {
      return new StubZohoBooksClient(clock);
    }
    return new LiveZohoBooksClient(
        clientId,
        clientSecret,
        accountsBaseUrl,
        booksBaseUrl,
        objectMapper,
        IntegrationConfig::httpZoho);
  }

  @Bean
  @ConditionalOnMissingBean(AccountingExportObjectStore.class)
  AccountingExportObjectStore accountingExportObjectStore() {
    return new LocalAccountingExportObjectStore();
  }

  @Bean
  @ConditionalOnMissingBean(AccountingDataPort.class)
  AccountingDataPort emptyAccountingDataPort() {
    return new AccountingDataPort() {
      @Override
      public List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(AccountingPlanPort.class)
  AccountingPlanPort denyAccountingPlanPort() {
    return pharmacyId -> false;
  }

  @Bean(name = "accountingTokenCipher")
  @Qualifier("accountingTokenCipher")
  @ConditionalOnMissingBean(name = "accountingTokenCipher")
  AesGcmCipher accountingTokenCipher(@Value("${medmate.accounting.token-cipher-key:}") String key) {
    if (blank(key)) {
      // Local/CI default — 32 zero bytes Base64 (same pattern as MFA local key).
      return AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    String trimmed = key.trim();
    if (trimmed.length() == 64 && trimmed.matches("[0-9a-fA-F]+")) {
      return new AesGcmCipher(HexFormat.of().parseHex(trimmed));
    }
    return new AesGcmCipher(Base64.getDecoder().decode(trimmed));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  static String httpZoho(LiveZohoBooksClient.HttpRequest request) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(20));
      request.headers().forEach(builder::header);
      if ("GET".equalsIgnoreCase(request.method())) {
        builder.GET();
      } else {
        builder.POST(
            HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body()));
      }
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "ZOHO_UNAVAILABLE",
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
      throw new AppException("ZOHO_UNAVAILABLE", "Zoho Books HTTP call failed", 503);
    }
  }

  static String httpGetGov(URI uri) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "GSTN_API_UNAVAILABLE",
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
      throw new AppException("GSTN_API_UNAVAILABLE", "Government API HTTP call failed", 503);
    }
  }

  static String httpPostDigiLocker(LiveDigiLockerClient.TokenRequest request) {
    return exchange(request.uri(), request.headers(), request.body());
  }

  static String httpGsp(LiveGspClient.HttpRequest request) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(20));
      request.headers().forEach(builder::header);
      if ("GET".equalsIgnoreCase(request.method())) {
        builder.GET();
      } else {
        builder.POST(
            HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body()));
      }
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "NIC_PORTAL_UNAVAILABLE",
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
      throw new AppException("NIC_PORTAL_UNAVAILABLE", "GSP/NIC HTTP call failed", 503);
    }
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

  static String httpPost(LiveRazorpayClient.Request request) {
    return exchange(request.uri(), request.headers(), request.body());
  }

  static String httpPostX(LiveRazorpayXClient.Request request) {
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
            "RAZORPAY_UNAVAILABLE",
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
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay HTTP call failed", 503);
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200);
  }
}
