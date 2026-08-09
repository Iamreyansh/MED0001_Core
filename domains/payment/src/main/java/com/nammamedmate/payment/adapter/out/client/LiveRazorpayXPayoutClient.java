package com.nammamedmate.payment.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Live RazorpayX payouts API client. */
public final class LiveRazorpayXPayoutClient implements RazorpayXPayoutPort {

  private static final String PAYOUTS_URL = "https://api.razorpay.com/v1/payouts";

  private final String keyId;
  private final String keySecret;
  private final ObjectMapper mapper;
  private final Function<Request, String> httpPost;

  public LiveRazorpayXPayoutClient(
      String keyId, String keySecret, ObjectMapper mapper, Function<Request, String> httpPost) {
    this.keyId = keyId;
    this.keySecret = keySecret;
    this.mapper = mapper;
    this.httpPost = httpPost;
  }

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    if (request.amountPaise() <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> bodyMap = new LinkedHashMap<>();
    bodyMap.put("account_number", "XXXXXXXXXXXX" + nullToEmpty(request.accountLast4()));
    bodyMap.put("amount", request.amountPaise());
    bodyMap.put("currency", "INR");
    bodyMap.put("mode", "NEFT");
    bodyMap.put("purpose", "payout");
    bodyMap.put("fund_account_id", "fa_pharmacy_" + request.pharmacyId());
    bodyMap.put("reference_id", request.settlementId().toString());
    bodyMap.put("narration", "MedMate settlement " + request.settlementId());
    String body;
    try {
      body = mapper.writeValueAsString(bodyMap);
    } catch (Exception e) {
      throw new AppException("RAZORPAY_PAYOUT_FAILED", "Failed to build RazorpayX request", 502);
    }
    String response;
    try {
      response =
          httpPost.apply(
              new Request(
                  URI.create(PAYOUTS_URL),
                  Map.of("Authorization", basic, "Content-Type", "application/json"),
                  body));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAY_PAYOUT_FAILED", "RazorpayX payout failed", 502);
    }
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("id").asText(null);
      if (id == null || id.isBlank()) {
        throw new AppException(
            "RAZORPAY_PAYOUT_FAILED", "RazorpayX response missing payout id", 502);
      }
      return new PayoutResult(id, 4);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("RAZORPAY_PAYOUT_FAILED", "Invalid RazorpayX response", 502);
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
