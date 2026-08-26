package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.CashfreeClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Live Cashfree PG client (sandbox or production host by mode). */
public final class LiveCashfreeClient implements CashfreeClientPort {

  private static final String API_VERSION = "2023-08-01";
  private static final String LIVE_BASE = "https://api.cashfree.com/pg";
  private static final String SANDBOX_BASE = "https://sandbox.cashfree.com/pg";

  private final String appId;
  private final String secretKey;
  private final String webhookSecret;
  private final String mode;
  private final ObjectMapper mapper;
  private final Function<Request, String> http;

  public LiveCashfreeClient(
      String appId,
      String secretKey,
      String webhookSecret,
      String mode,
      ObjectMapper mapper,
      Function<Request, String> http) {
    this.appId = appId;
    this.secretKey = secretKey;
    this.webhookSecret = webhookSecret;
    this.mode = normalizeMode(mode);
    this.mapper = mapper;
    this.http = http;
  }

  static String normalizeMode(String mode) {
    if (mode == null || mode.isBlank()) {
      return "TEST";
    }
    return mode.trim().toUpperCase(Locale.ROOT);
  }

  private String base() {
    return "LIVE".equals(mode) ? LIVE_BASE : SANDBOX_BASE;
  }

  @Override
  public CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    String orderId =
        receipt != null && !receipt.isBlank()
            ? receipt.trim()
            : "cf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("order_id", orderId);
    body.put("order_amount", amountPaise / 100.0);
    body.put("order_currency", currency == null || currency.isBlank() ? "INR" : currency);
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("customer_id", orderId);
    customer.put("customer_phone", "9999999999");
    body.put("customer_details", customer);
    if (notes != null && !notes.isEmpty()) {
      body.put("order_note", notes.toString());
    }
    JsonNode root = postJson(base() + "/orders", body, "CASHFREE_UNAVAILABLE");
    String id = firstText(root, "order_id", "cf_order_id", "id");
    if (id == null) {
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree order id missing", 503);
    }
    String session = root.path("payment_session_id").asText("");
    return new CreateOrderResult(
        id,
        session,
        amountPaise,
        currency == null || currency.isBlank() ? "INR" : currency,
        receipt,
        root.path("order_status").asText("ACTIVE"));
  }

  @Override
  public CaptureResult capturePayment(String gatewayPaymentId, long amountPaise) {
    // Cashfree auto-captures for most PG flows; treat as acknowledged.
    if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment id required", 400);
    }
    return new CaptureResult(gatewayPaymentId.trim(), "captured");
  }

  @Override
  public UpiVerifyResult verifyUpi(String vpa) {
    Map<String, Object> body = Map.of("vpa", vpa);
    JsonNode root = postJson(base() + "/orders/upi/validate", body, "CASHFREE_UNAVAILABLE");
    boolean valid =
        root.path("valid").asBoolean(false)
            || root.path("account_exists").asBoolean(false)
            || root.path("success").asBoolean(false);
    String name = root.path("name").asText(null);
    if (name == null || name.isBlank()) {
      name = root.path("customer_name").asText(null);
    }
    if (name == null || name.isBlank()) {
      name = root.path("account_holder_name").asText(null);
    }
    return new UpiVerifyResult(vpa, valid, name);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    return verifyWebhookSignature(signatureHeader, null, rawBody);
  }

  @Override
  public boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    return CashfreeHmac.verify(webhookSecret, signatureHeader, timestampHeader, rawBody);
  }

  @Override
  public String mode() {
    return mode;
  }

  private static String firstText(JsonNode root, String... fields) {
    for (String f : fields) {
      String v = root.path(f).asText(null);
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private JsonNode postJson(String url, Map<String, Object> body, String errorCode) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new AppException(errorCode, "Failed to build Cashfree request", 503);
    }
    String response;
    try {
      response =
          http.apply(
              new Request(
                  URI.create(url),
                  Map.of(
                      "x-client-id",
                      appId,
                      "x-client-secret",
                      secretKey,
                      "x-api-version",
                      API_VERSION,
                      "Content-Type",
                      "application/json"),
                  json));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException(errorCode, "Cashfree API returned error", 503);
    }
    try {
      return mapper.readTree(response);
    } catch (Exception e) {
      throw new AppException(errorCode, "Cashfree response unreadable", 503);
    }
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
