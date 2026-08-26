package com.nammamedmate.payment.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Live Cashfree Payouts transfer client. */
public final class LiveCashfreePayoutClient implements CashfreePayoutPort {

  private static final String TRANSFER_URL = "https://api.cashfree.com/payout/v1/requestTransfer";

  private final String clientId;
  private final String clientSecret;
  private final ObjectMapper mapper;
  private final Function<Request, String> httpPost;

  public LiveCashfreePayoutClient(
      String clientId,
      String clientSecret,
      ObjectMapper mapper,
      Function<Request, String> httpPost) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.mapper = mapper;
    this.httpPost = httpPost;
  }

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    if (request.amountPaise() <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    Map<String, Object> bodyMap = new LinkedHashMap<>();
    bodyMap.put("beneId", "pharmacy_" + request.pharmacyId());
    bodyMap.put("amount", request.amountPaise() / 100.0);
    bodyMap.put("transferId", request.settlementId().toString());
    bodyMap.put("transferMode", "NEFT");
    bodyMap.put("remarks", "MedMate settlement " + request.settlementId());
    String body;
    try {
      body = mapper.writeValueAsString(bodyMap);
    } catch (Exception e) {
      throw new AppException(
          "CASHFREE_PAYOUT_FAILED", "Failed to build Cashfree payouts request", 502);
    }
    String response;
    try {
      response =
          httpPost.apply(
              new Request(
                  URI.create(TRANSFER_URL),
                  Map.of(
                      "x-client-id",
                      clientId,
                      "x-client-secret",
                      clientSecret,
                      "Content-Type",
                      "application/json"),
                  body));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_PAYOUT_FAILED", "Cashfree payout failed", 502);
    }
    try {
      JsonNode root = mapper.readTree(response);
      String id = root.path("transferId").asText(null);
      if (id == null || id.isBlank()) {
        id = root.path("data").path("transferId").asText(null);
      }
      if (id == null || id.isBlank()) {
        id = root.path("id").asText(null);
      }
      if (id == null || id.isBlank()) {
        throw new AppException(
            "CASHFREE_PAYOUT_FAILED", "Cashfree response missing transfer id", 502);
      }
      return new PayoutResult(id, 4);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("CASHFREE_PAYOUT_FAILED", "Invalid Cashfree payouts response", 502);
    }
  }

  public record Request(URI uri, Map<String, String> headers, String body) {
    public Request {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
