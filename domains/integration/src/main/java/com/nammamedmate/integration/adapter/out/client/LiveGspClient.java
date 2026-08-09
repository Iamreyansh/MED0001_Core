package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Live GSP client with JWT auth. Credentials never logged. */
public final class LiveGspClient implements GspClientPort {

  public record HttpRequest(URI uri, Map<String, String> headers, String body, String method) {
    public HttpRequest {
      headers = Map.copyOf(headers);
    }
  }

  private final String clientId;
  private final String clientSecret;
  private final String baseUrl;
  private final ObjectMapper mapper;
  private final Function<HttpRequest, String> http;
  private final AtomicReference<TokenState> token = new AtomicReference<>();

  public LiveGspClient(
      String clientId,
      String clientSecret,
      String baseUrl,
      ObjectMapper mapper,
      Function<HttpRequest, String> http) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.mapper = mapper;
    this.http = http;
  }

  @Override
  public IrnResult generateIrn(Map<String, Object> invoiceData) {
    ensureToken();
    try {
      String body = mapper.writeValueAsString(Map.of("invoice_data", invoiceData));
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(baseUrl + "/einvoice/generate"), authHeaders(), body, "POST"));
      JsonNode root = mapper.readTree(response);
      if (root.path("error_code").asText("").equals("SELLER_GSTIN_NOT_REGISTERED")) {
        throw new AppException(
            "SELLER_GSTIN_NOT_REGISTERED", "Seller GSTIN not found in e-invoice portal", 422);
      }
      if (root.path("error_code").asText("").equals("DUPLICATE_IRN")) {
        throw new AppException(
            "DUPLICATE_IRN",
            "IRN already exists for this invoice",
            422,
            null,
            Map.of("irn", root.path("irn").asText(""), "already_existed", true));
      }
      return new IrnResult(
          text(root, "irn"),
          text(root, "ack_number"),
          parseInstant(root.path("ack_date").asText(null)),
          text(root, "qr_code_url"),
          text(root, "signed_invoice_json"));
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {
    ensureToken();
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("irn", irn);
      payload.put("cancel_reason_code", cancelReasonCode);
      payload.put("cancel_remark", cancelRemark);
      String body = mapper.writeValueAsString(payload);
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(baseUrl + "/einvoice/cancel"), authHeaders(), body, "POST"));
      JsonNode root = mapper.readTree(response);
      String code = root.path("error_code").asText("");
      if ("IRN_NOT_FOUND".equals(code)) {
        throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
      }
      if ("IRN_ALREADY_CANCELLED".equals(code)) {
        throw new AppException("IRN_ALREADY_CANCELLED", "IRN already cancelled", 422);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public IrnStatusResult getStatus(String irn) {
    ensureToken();
    try {
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(baseUrl + "/einvoice/status/" + irn), authHeaders(), null, "GET"));
      JsonNode root = mapper.readTree(response);
      if ("IRN_NOT_FOUND".equals(root.path("error_code").asText(""))) {
        throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
      }
      Instant cancelled =
          root.path("cancelled_at").isNull() || root.path("cancelled_at").asText("").isBlank()
              ? null
              : parseInstant(root.path("cancelled_at").asText());
      return new IrnStatusResult(
          text(root, "irn"),
          text(root, "status"),
          text(root, "ack_number"),
          parseInstant(root.path("ack_date").asText(null)),
          cancelled);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public TokenState refreshToken() {
    try {
      String body =
          mapper.writeValueAsString(
              Map.of(
                  "client_id",
                  clientId,
                  "client_secret",
                  clientSecret,
                  "grant_type",
                  "client_credentials"));
      String response =
          http.apply(
              new HttpRequest(
                  URI.create(baseUrl + "/auth/token"),
                  Map.of("Content-Type", "application/json"),
                  body,
                  "POST"));
      JsonNode root = mapper.readTree(response);
      String access = text(root, "access_token");
      long expiresIn = root.path("expires_in").asLong(86400);
      TokenState next = new TokenState(access, Instant.now().plusSeconds(expiresIn));
      token.set(next);
      return next;
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public Optional<TokenState> currentToken() {
    return Optional.ofNullable(token.get());
  }

  private void ensureToken() {
    TokenState current = token.get();
    if (current == null || !current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
      refreshToken();
    }
  }

  private Map<String, String> authHeaders() {
    TokenState current = token.get();
    // ensureToken() always populates token before authHeaders is used
    return Map.of(
        "Content-Type", "application/json", "Authorization", "Bearer " + current.accessToken());
  }

  private static AppException unavailable() {
    return new AppException("NIC_PORTAL_UNAVAILABLE", "NIC portal unreachable", 503);
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return n == null || n.isNull() ? null : n.asText();
  }

  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) {
      return Instant.now();
    }
    return Instant.parse(raw);
  }
}
