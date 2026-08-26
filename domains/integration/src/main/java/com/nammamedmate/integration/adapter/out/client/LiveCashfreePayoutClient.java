package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.CashfreePayoutClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Live Cashfree Payouts client (separate payouts credentials). */
public final class LiveCashfreePayoutClient implements CashfreePayoutClientPort {

  private static final String BASE = "https://api.cashfree.com/payout";

  private final String clientId;
  private final String clientSecret;
  private final ObjectMapper mapper;
  private final Function<Request, String> http;

  public LiveCashfreePayoutClient(
      String clientId, String clientSecret, ObjectMapper mapper, Function<Request, String> http) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.mapper = mapper;
    this.http = http;
  }

  @Override
  public BeneficiaryResult createBeneficiary(CreateBeneficiaryRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("beneId", request.entityType() + "_" + request.entityId());
    body.put("name", request.accountHolderName());
    body.put("email", "payouts@nammamedmate.com");
    body.put("phone", "9999999999");
    body.put("bankAccount", request.accountNumber());
    body.put("ifsc", request.ifsc());
    body.put("address1", "India");
    JsonNode root = postJson(BASE + "/v1/addBeneficiary", body);
    String beneId = firstText(root, "beneId", "beneficiary_id", "id");
    if (beneId == null) {
      throw new AppException(
          "CASHFREE_PAYOUTS_UNAVAILABLE", "Cashfree beneficiary id missing", 503);
    }
    return new BeneficiaryResult(beneId, beneId);
  }

  @Override
  public PayoutResult createPayout(CreatePayoutRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("beneId", request.beneficiaryId());
    body.put("amount", request.amountPaise() / 100.0);
    body.put("transferId", request.referenceId());
    body.put("transferMode", request.mode());
    body.put("remarks", request.purpose() == null ? "payout" : request.purpose());
    JsonNode root;
    try {
      root = postJson(BASE + "/v1/requestTransfer", body);
    } catch (AppException e) {
      if (messageMentionsBalance(e.getMessage())) {
        throw new AppException(
            "INSUFFICIENT_BALANCE", "Cashfree payouts balance insufficient", 422);
      }
      throw e;
    }
    String id = firstText(root, "transferId", "referenceId", "id");
    if (id == null) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "Cashfree transfer id missing", 503);
    }
    return new PayoutResult(id, root.path("status").asText("PENDING"));
  }

  private static String firstText(JsonNode root, String... fields) {
    for (String f : fields) {
      String v = root.path(f).asText(null);
      if (v != null && !v.isBlank()) {
        return v;
      }
      v = root.path("data").path(f).asText(null);
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private JsonNode postJson(String url, Map<String, Object> body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new AppException(
          "CASHFREE_PAYOUTS_UNAVAILABLE", "Failed to build Cashfree payouts request", 503);
    }
    String response;
    try {
      response =
          http.apply(
              new Request(
                  URI.create(url),
                  Map.of(
                      "x-client-id",
                      clientId,
                      "x-client-secret",
                      clientSecret,
                      "Content-Type",
                      "application/json"),
                  json));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "Cashfree payouts API error", 503);
    }
    try {
      JsonNode root = mapper.readTree(response);
      if (root.has("error") || "ERROR".equalsIgnoreCase(root.path("status").asText(""))) {
        String desc =
            root.path("message")
                .asText(
                    root.path("error")
                        .path("description")
                        .asText(root.path("error").path("message").asText("payouts error")));
        if (messageMentionsBalance(desc)
            || messageMentionsBalance(root.path("error").path("code").asText(""))) {
          throw new AppException("INSUFFICIENT_BALANCE", desc, 422);
        }
        throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", desc, 503);
      }
      return root;
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException(
          "CASHFREE_PAYOUTS_UNAVAILABLE", "Cashfree payouts response unreadable", 503);
    }
  }

  static boolean messageMentionsBalance(String message) {
    return message != null && message.toLowerCase().contains("balance");
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
