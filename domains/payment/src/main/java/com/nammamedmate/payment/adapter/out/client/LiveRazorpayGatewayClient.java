package com.nammamedmate.payment.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Live Razorpay Orders API client (create + HMAC verify). */
public final class LiveRazorpayGatewayClient implements RazorpayGatewayPort {

  private static final String ORDERS_URL = "https://api.razorpay.com/v1/orders";
  private static final String PAYMENTS_URL = "https://api.razorpay.com/v1/payments";

  private final String keyId;
  private final String keySecret;
  private final String webhookSecret;
  private final ObjectMapper mapper;
  private final Function<Request, String> httpPost;

  public LiveRazorpayGatewayClient(
      String keyId,
      String keySecret,
      String webhookSecret,
      ObjectMapper mapper,
      Function<Request, String> httpPost) {
    this.keyId = keyId;
    this.keySecret = keySecret;
    this.webhookSecret = webhookSecret;
    this.mapper = mapper;
    this.httpPost = httpPost;
  }

  @Override
  public CreateOrderResult createOrder(UUID medmateOrderId, long amountPaise) {
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    String body;
    try {
      body =
          mapper.writeValueAsString(
              Map.of(
                  "amount",
                  amountPaise,
                  "currency",
                  "INR",
                  "receipt",
                  medmateOrderId.toString().replace("-", "").substring(0, 20),
                  "payment_capture",
                  1));
    } catch (Exception e) {
      throw new AppException("RAZORPAY_ERROR", "Failed to build Razorpay order request", 502);
    }
    String response;
    try {
      response =
          httpPost.apply(
              new Request(
                  URI.create(ORDERS_URL),
                  Map.of("Authorization", basic, "Content-Type", "application/json"),
                  body));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAY_ERROR", "Razorpay order creation failed", 502);
    }
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("id").asText(null);
      if (id == null) {
        throw new AppException("RAZORPAY_ERROR", "Razorpay order id missing", 502);
      }
      if (id.isBlank()) {
        throw new AppException("RAZORPAY_ERROR", "Razorpay order id missing", 502);
      }
      return new CreateOrderResult(id, amountPaise, keyId);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("RAZORPAY_ERROR", "Razorpay order response unreadable", 502);
    }
  }

  @Override
  public boolean verifyPaymentSignature(
      String razorpayOrderId, String paymentId, String signature) {
    if (razorpayOrderId == null) {
      return false;
    }
    if (paymentId == null) {
      return false;
    }
    if (signature == null) {
      return false;
    }
    if (signature.isBlank()) {
      return false;
    }
    String expected = signPayment(razorpayOrderId, paymentId);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String signPayment(String razorpayOrderId, String paymentId) {
    return hmacHex(keySecret, razorpayOrderId + "|" + paymentId);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null) {
      return false;
    }
    if (rawBody == null) {
      return false;
    }
    String expected = hmacHex(webhookSecret, new String(rawBody, StandardCharsets.UTF_8));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public RefundResult refund(String razorpayPaymentId, long amountPaise) {
    if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "razorpay payment id required", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    String body;
    try {
      body = mapper.writeValueAsString(Map.of("amount", amountPaise));
    } catch (Exception e) {
      throw new AppException(
          "RAZORPAY_REFUND_FAILED", "Failed to build Razorpay refund request", 502);
    }
    String response;
    try {
      response =
          httpPost.apply(
              new Request(
                  URI.create(PAYMENTS_URL + "/" + razorpayPaymentId.trim() + "/refund"),
                  Map.of("Authorization", basic, "Content-Type", "application/json"),
                  body));
    } catch (AppException e) {
      if ("RAZORPAY_ERROR".equals(e.code())) {
        throw new AppException("RAZORPAY_REFUND_FAILED", e.getMessage(), 502);
      }
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAY_REFUND_FAILED", "Razorpay refund failed", 502);
    }
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("id").asText(null);
      if (id == null || id.isBlank()) {
        throw new AppException("RAZORPAY_REFUND_FAILED", "Razorpay refund id missing", 502);
      }
      return new RefundResult(id, amountPaise);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("RAZORPAY_REFUND_FAILED", "Razorpay refund response unreadable", 502);
    }
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = Map.copyOf(headers);
    }
  }

  static String hmacHex(String secret, String payload) {
    return hmacHex(secret, payload, "HmacSHA256");
  }

  static String hmacHex(String secret, String payload, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}
