package com.nammamedmate.customer.adapter.out.cashfree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.port.out.CashfreeVpaPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/** Cashfree {@code GET /v1/payments/validate/vpa} client. */
public final class CashfreeVpaClient implements CashfreeVpaPort {

  private static final String ENDPOINT =
      "https://api.cashfree.com/pg/payments/validate/vpa?address=%s";

  private final String basicAuthHeader;
  private final ObjectMapper mapper;
  private final Function<Request, String> httpGet;

  public CashfreeVpaClient(
      String keyId, String keySecret, ObjectMapper mapper, Function<Request, String> httpGet) {
    this.basicAuthHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public boolean validateVpa(String vpa) {
    URI uri = URI.create(ENDPOINT.formatted(URLEncoder.encode(vpa, StandardCharsets.UTF_8)));
    String body;
    try {
      body = httpGet.apply(new Request(uri, Map.of("Authorization", basicAuthHeader)));
    } catch (AppException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new AppException("VPA_VALIDATION_TIMEOUT", "Cashfree VPA validation timed out", 503);
    }
    try {
      JsonNode root = mapper.readTree(body);
      return root.path("success").asBoolean(false);
    } catch (Exception ex) {
      throw new AppException(
          "VPA_VALIDATION_FAILED", "Cashfree VPA validation response was unreadable", 503);
    }
  }

  public record Request(URI uri, Map<String, String> headers) {
    public Request {
      headers = Map.copyOf(headers);
    }
  }
}
