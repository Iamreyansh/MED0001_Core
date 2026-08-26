package com.nammamedmate.payment.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Live Cashfree PG Orders API client. */
public final class LiveCashfreeGatewayClient implements CashfreeGatewayPort {

  private static final String API_VERSION = "2023-08-01";
  private static final String LIVE_BASE = "https://api.cashfree.com/pg";
  private static final String SANDBOX_BASE = "https://sandbox.cashfree.com/pg";

  private final String appId;
  private final String secretKey;
  private final String webhookSecret;
  private final boolean live;
  private final ObjectMapper mapper;
  private final Function<Request, String> httpPost;

  public LiveCashfreeGatewayClient(
      String appId,
      String secretKey,
      String webhookSecret,
      ObjectMapper mapper,
      Function<Request, String> httpPost) {
    this(appId, secretKey, webhookSecret, false, mapper, httpPost);
  }

  public LiveCashfreeGatewayClient(
      String appId,
      String secretKey,
      String webhookSecret,
      boolean live,
      ObjectMapper mapper,
      Function<Request, String> httpPost) {
    this.appId = appId;
    this.secretKey = secretKey;
    this.webhookSecret = webhookSecret;
    this.live = live;
    this.mapper = mapper;
    this.httpPost = httpPost;
  }

  private String base() {
    return live ? LIVE_BASE : SANDBOX_BASE;
  }

  @Override
  public CreateOrderResult createOrder(UUID medmateOrderId, long amountPaise) {
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String orderId = medmateOrderId.toString().replace("-", "").substring(0, 20);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("order_id", orderId);
    body.put("order_amount", amountPaise / 100.0);
    body.put("order_currency", "INR");
    body.put(
        "customer_details",
        Map.of("customer_id", medmateOrderId.toString(), "customer_phone", "9999999999"));
    String response = post(base() + "/orders", body, "CASHFREE_ERROR");
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("order_id").asText(null);
      if (id == null || id.isBlank()) {
        id = root.path("cf_order_id").asText(null);
      }
      if (id == null || id.isBlank()) {
        id = root.path("id").asText(null);
      }
      if (id == null || id.isBlank()) {
        throw new AppException("CASHFREE_ERROR", "Cashfree order id missing", 502);
      }
      String session = root.path("payment_session_id").asText("");
      return new CreateOrderResult(id, session, amountPaise, appId);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("CASHFREE_ERROR", "Cashfree order response unreadable", 502);
    }
  }

  @Override
  public boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature) {
    if (gatewayOrderId == null || paymentId == null || signature == null || signature.isBlank()) {
      return false;
    }
    String expected = signPayment(gatewayOrderId, paymentId);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String signPayment(String gatewayOrderId, String paymentId) {
    return hmacHex(secretKey, gatewayOrderId + "|" + paymentId);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    return verifyWebhookSignature(signatureHeader, null, rawBody);
  }

  @Override
  public boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    if (signatureHeader == null || rawBody == null) {
      return false;
    }
    String ts = timestampHeader == null ? "" : timestampHeader;
    String payload = ts + new String(rawBody, StandardCharsets.UTF_8);
    String expectedHex = hmacHex(webhookSecret, payload);
    String expectedB64 =
        Base64.getEncoder().encodeToString(hmacBytes(webhookSecret, payload, "HmacSHA256"));
    byte[] sig = signatureHeader.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), sig)
        || MessageDigest.isEqual(expectedB64.getBytes(StandardCharsets.UTF_8), sig);
  }

  @Override
  public String keyId() {
    return appId;
  }

  @Override
  public RefundResult refund(String gatewayPaymentId, long amountPaise) {
    if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "gateway payment id required", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("refund_amount", amountPaise / 100.0);
    body.put("refund_id", "rfnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    body.put("payment_id", gatewayPaymentId.trim());
    String response;
    try {
      response = post(base() + "/orders/refunds", body, "CASHFREE_REFUND_FAILED");
    } catch (AppException e) {
      if ("CASHFREE_ERROR".equals(e.code())) {
        throw new AppException("CASHFREE_REFUND_FAILED", e.getMessage(), 502);
      }
      throw e;
    }
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("refund_id").asText(null);
      if (id == null || id.isBlank()) {
        id = root.path("cf_refund_id").asText(null);
      }
      if (id == null || id.isBlank()) {
        id = root.path("id").asText(null);
      }
      if (id == null || id.isBlank()) {
        throw new AppException("CASHFREE_REFUND_FAILED", "Cashfree refund id missing", 502);
      }
      return new RefundResult(id, amountPaise);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("CASHFREE_REFUND_FAILED", "Cashfree refund response unreadable", 502);
    }
  }

  private String post(String url, Map<String, Object> body, String errorCode) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new AppException(errorCode, "Failed to build Cashfree request", 502);
    }
    try {
      return httpPost.apply(
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
      throw new AppException(errorCode, "Cashfree request failed", 502);
    }
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }

  static String hmacHex(String secret, String payload) {
    return hmacHex(secret, payload, "HmacSHA256");
  }

  static String hmacHex(String secret, String payload, String algorithm) {
    return HexFormat.of().formatHex(hmacBytes(secret, payload, algorithm));
  }

  static byte[] hmacBytes(String secret, String payload, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}
