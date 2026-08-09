package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Live Razorpay Orders / Payments / VPA client (api.razorpay.com/v1). */
public final class LiveRazorpayClient implements RazorpayClientPort {

  private static final String BASE = "https://api.razorpay.com/v1";

  private final String keyId;
  private final String keySecret;
  private final String webhookSecret;
  private final String mode;
  private final ObjectMapper mapper;
  private final Function<Request, String> http;

  public LiveRazorpayClient(
      String keyId,
      String keySecret,
      String webhookSecret,
      String mode,
      ObjectMapper mapper,
      Function<Request, String> http) {
    this.keyId = keyId;
    this.keySecret = keySecret;
    this.webhookSecret = webhookSecret;
    this.mode = normalizeMode(mode);
    this.mapper = mapper;
    this.http = http;
  }

  static String normalizeMode(String mode) {
    if (mode == null) {
      return "TEST";
    }
    if (mode.isBlank()) {
      return "TEST";
    }
    return mode.trim().toUpperCase(Locale.ROOT);
  }

  @Override
  public CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", amountPaise);
    body.put("currency", currency);
    body.put("receipt", receipt);
    body.put("payment_capture", 0);
    if (notes != null && !notes.isEmpty()) {
      body.put("notes", notes);
    }
    JsonNode root = postJson(BASE + "/orders", body, "RAZORPAY_UNAVAILABLE");
    String id = root.path("id").asText(null);
    if (id == null || id.isBlank()) {
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay order id missing", 503);
    }
    return new CreateOrderResult(
        id, amountPaise, currency, receipt, root.path("status").asText("created"));
  }

  @Override
  public CaptureResult capturePayment(String razorpayPaymentId, long amountPaise) {
    Map<String, Object> body = Map.of("amount", amountPaise, "currency", "INR");
    JsonNode root =
        postJson(
            BASE + "/payments/" + razorpayPaymentId + "/capture", body, "RAZORPAY_UNAVAILABLE");
    return new CaptureResult(razorpayPaymentId, root.path("status").asText("captured"));
  }

  @Override
  public UpiVerifyResult verifyUpi(String vpa) {
    Map<String, Object> body = Map.of("vpa", vpa);
    JsonNode root = postJson(BASE + "/payments/validate/vpa", body, "RAZORPAY_UNAVAILABLE");
    boolean valid = root.path("success").asBoolean(false);
    String name = root.path("customer_name").asText(null);
    if (name == null || name.isBlank()) {
      name = root.path("account_holder_name").asText(null);
    }
    return new UpiVerifyResult(vpa, valid, name);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null) {
      return false;
    }
    if (rawBody == null) {
      return false;
    }
    String expected =
        RazorpayHmac.hmacHex(webhookSecret, new String(rawBody, StandardCharsets.UTF_8));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String mode() {
    return mode;
  }

  private JsonNode postJson(String url, Map<String, Object> body, String errorCode) {
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new AppException(errorCode, "Failed to build Razorpay request", 503);
    }
    String response;
    try {
      response =
          http.apply(
              new Request(
                  URI.create(url),
                  Map.of("Authorization", basic, "Content-Type", "application/json"),
                  json));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException(errorCode, "Razorpay API returned error", 503);
    }
    try {
      return mapper.readTree(response);
    } catch (Exception e) {
      throw new AppException(errorCode, "Razorpay response unreadable", 503);
    }
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
