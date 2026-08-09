package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.ZohoBooksClientPort;
import com.nammamedmate.integration.domain.AccountingVoucher;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Live Zoho Books API client (OAuth refresh + voucher upsert). */
public final class LiveZohoBooksClient implements ZohoBooksClientPort {

  public record HttpRequest(URI uri, Map<String, String> headers, String body, String method) {
    public HttpRequest {
      headers = Map.copyOf(headers);
    }
  }

  private final String clientId;
  private final String clientSecret;
  private final String accountsBaseUrl;
  private final String booksBaseUrl;
  private final ObjectMapper mapper;
  private final Function<HttpRequest, String> http;

  public LiveZohoBooksClient(
      String clientId,
      String clientSecret,
      String accountsBaseUrl,
      String booksBaseUrl,
      ObjectMapper mapper,
      Function<HttpRequest, String> http) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.accountsBaseUrl = trimSlash(accountsBaseUrl);
    this.booksBaseUrl = trimSlash(booksBaseUrl);
    this.mapper = mapper;
    this.http = http;
  }

  @Override
  public TokenPair refreshAccessToken(String refreshToken) {
    try {
      String body =
          "refresh_token="
              + refreshToken
              + "&client_id="
              + clientId
              + "&client_secret="
              + clientSecret
              + "&grant_type=refresh_token";
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(accountsBaseUrl + "/oauth/v2/token"),
                  Map.of("Content-Type", "application/x-www-form-urlencoded"),
                  body,
                  "POST"));
      JsonNode root = mapper.readTree(response);
      String access = root.path("access_token").asText(null);
      if (access == null || access.isBlank()) {
        throw unavailable();
      }
      String refresh = root.path("refresh_token").asText(refreshToken);
      long expiresIn = root.path("expires_in").asLong(3600);
      return new TokenPair(access, refresh, Instant.now().plusSeconds(expiresIn));
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public SyncResult upsertSalesVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert(accessToken, organizationId, "invoices", voucher);
  }

  @Override
  public SyncResult upsertPurchaseVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert(accessToken, organizationId, "bills", voucher);
  }

  @Override
  public SyncResult upsertGstEntry(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert(accessToken, organizationId, "journals", voucher);
  }

  @Override
  public SyncResult upsertExpense(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert(accessToken, organizationId, "expenses", voucher);
  }

  private SyncResult upsert(
      String accessToken, String organizationId, String resource, AccountingVoucher voucher) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("reference_number", voucher.platformId().toString());
      payload.put("voucher_number", voucher.voucherNumber());
      payload.put("date", voucher.voucherDate().toString());
      payload.put("customer_name", voucher.partyName());
      payload.put("gst_no", voucher.partyGstin());
      payload.put("total", voucher.totalPaise() / 100.0);
      String body = mapper.writeValueAsString(payload);
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(booksBaseUrl + "/" + resource + "?organization_id=" + organizationId),
                  Map.of(
                      "Authorization",
                      "Zoho-oauthtoken " + accessToken,
                      "Content-Type",
                      "application/json"),
                  body,
                  "POST"));
      JsonNode root = mapper.readTree(response);
      String code = root.path("code").asText("");
      if ("3041".equals(code) || "duplicate".equalsIgnoreCase(root.path("message").asText(""))) {
        return SyncResult.ok(root.path("invoice").path("invoice_id").asText("existing"), false);
      }
      if (root.has("error_code") || root.path("code").asInt(0) >= 4000) {
        return SyncResult.fail(
            root.path("error_code").asText("ZOHO_ERROR"),
            root.path("message").asText("Zoho Books API error"));
      }
      String id =
          firstNonBlank(
              root.path("invoice").path("invoice_id").asText(null),
              root.path("bill").path("bill_id").asText(null),
              root.path("journal").path("journal_id").asText(null),
              root.path("expense").path("expense_id").asText(null),
              root.path("invoice_id").asText(null));
      return SyncResult.ok(id == null ? "zoho-ok" : id, true);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static AppException unavailable() {
    return new AppException("ZOHO_UNAVAILABLE", "Zoho Books API unavailable", 503);
  }
}
