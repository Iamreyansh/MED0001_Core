package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;

/** Live GSTN portal client. Credentials never logged. */
public final class LiveGstnClient implements GstnClientPort {

  private final String apiKey;
  private final String baseUrl;
  private final ObjectMapper mapper;
  private final Function<URI, String> httpGet;

  public LiveGstnClient(
      String apiKey, String baseUrl, ObjectMapper mapper, Function<URI, String> httpGet) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public Optional<GstnResult> verify(String gstin) {
    try {
      URI uri = URI.create(baseUrl + "/gstin/" + gstin + "?key=" + apiKey);
      String body = httpGet.apply(uri);
      JsonNode root = mapper.readTree(body);
      if (root.path("found").asBoolean(false) || root.has("trade_name")) {
        return Optional.of(
            new GstnResult(
                true,
                root.path("valid").asBoolean(true),
                text(root, "trade_name"),
                text(root, "legal_name"),
                text(root, "registration_status"),
                text(root, "filing_status"),
                text(root, "state"),
                text(root, "state_code"),
                parseDate(root.path("registered_at").asText(null))));
      }
      return Optional.empty();
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("GSTN_API_UNAVAILABLE", "GSTN portal unreachable", 503);
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return n == null || n.isNull() ? null : n.asText();
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
  }
}
