package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Live RazorpayX contacts / fund accounts / payouts client. */
public final class LiveRazorpayXClient implements RazorpayXClientPort {

  private static final String BASE = "https://api.razorpay.com/v1";

  private final String keyId;
  private final String keySecret;
  private final ObjectMapper mapper;
  private final Function<Request, String> http;

  public LiveRazorpayXClient(
      String keyId, String keySecret, ObjectMapper mapper, Function<Request, String> http) {
    this.keyId = keyId;
    this.keySecret = keySecret;
    this.mapper = mapper;
    this.http = http;
  }

  @Override
  public FundAccountResult createFundAccount(CreateFundAccountRequest request) {
    Map<String, Object> contactBody = new LinkedHashMap<>();
    contactBody.put("name", request.accountHolderName());
    contactBody.put("type", "vendor");
    contactBody.put("reference_id", request.entityType() + ":" + request.entityId());
    JsonNode contact = postJson(BASE + "/contacts", contactBody);
    String contactId = contact.path("id").asText(null);
    if (contactId == null || contactId.isBlank()) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX contact id missing", 503);
    }
    Map<String, Object> bank = new LinkedHashMap<>();
    bank.put("name", request.accountHolderName());
    bank.put("ifsc", request.ifsc());
    bank.put("account_number", request.accountNumber());
    Map<String, Object> faBody = new LinkedHashMap<>();
    faBody.put("contact_id", contactId);
    faBody.put("account_type", "bank_account");
    faBody.put("bank_account", bank);
    JsonNode fa = postJson(BASE + "/fund_accounts", faBody);
    String faId = fa.path("id").asText(null);
    if (faId == null || faId.isBlank()) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX fund account id missing", 503);
    }
    return new FundAccountResult(contactId, faId);
  }

  @Override
  public PayoutResult createPayout(CreatePayoutRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("account_number", "from_config");
    body.put("fund_account_id", request.fundAccountId());
    body.put("amount", request.amountPaise());
    body.put("currency", "INR");
    body.put("mode", request.mode());
    body.put("purpose", request.purpose());
    body.put("queue_if_low_balance", true);
    body.put("reference_id", request.referenceId());
    if (request.notes() != null && !request.notes().isEmpty()) {
      body.put("notes", request.notes());
    }
    JsonNode root;
    try {
      root = postJson(BASE + "/payouts", body);
    } catch (AppException e) {
      if (messageMentionsBalance(e.getMessage())) {
        throw new AppException(
            "INSUFFICIENT_BALANCE", "RazorpayX account balance insufficient", 422);
      }
      throw e;
    }
    String id = root.path("id").asText(null);
    if (id == null || id.isBlank()) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX payout id missing", 503);
    }
    return new PayoutResult(id, root.path("status").asText("processing"));
  }

  private JsonNode postJson(String url, Map<String, Object> body) {
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "Failed to build RazorpayX request", 503);
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
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX API error", 503);
    }
    try {
      JsonNode root = mapper.readTree(response);
      if (root.has("error")) {
        String desc = root.path("error").path("description").asText("RazorpayX API error");
        String code = root.path("error").path("code").asText("");
        if (messageMentionsBalance(code) || messageMentionsBalance(desc)) {
          throw new AppException("INSUFFICIENT_BALANCE", desc, 422);
        }
        throw new AppException("RAZORPAYX_UNAVAILABLE", desc, 503);
      }
      return root;
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX response unreadable", 503);
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
