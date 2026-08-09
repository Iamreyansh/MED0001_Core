package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.FssaiClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;

/** Live FSSAI portal client. Credentials never logged. */
public final class LiveFssaiClient implements FssaiClientPort {

  private final String apiKey;
  private final String baseUrl;
  private final ObjectMapper mapper;
  private final Function<URI, String> httpGet;

  public LiveFssaiClient(
      String apiKey, String baseUrl, ObjectMapper mapper, Function<URI, String> httpGet) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public Optional<FssaiResult> verify(String licenceNumber) {
    try {
      URI uri =
          URI.create(
              baseUrl
                  + "/licence?number="
                  + URLEncoder.encode(licenceNumber, StandardCharsets.UTF_8)
                  + "&key="
                  + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
      JsonNode root = mapper.readTree(httpGet.apply(uri));
      if (root.path("manual_review").asBoolean(false)) {
        return Optional.of(
            new FssaiResult(false, false, true, null, null, null, "MANUAL_REVIEW_REQUIRED"));
      }
      if (!root.path("found").asBoolean(root.has("business_name"))) {
        return Optional.empty();
      }
      LocalDate expiry = parseDate(root.path("expiry_date").asText(null));
      boolean expired = expiry != null && expiry.isBefore(LocalDate.now());
      return Optional.of(
          new FssaiResult(
              true,
              !expired,
              false,
              text(root, "business_name"),
              text(root, "category"),
              expiry,
              expired ? "EXPIRED" : text(root, "status")));
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      return Optional.of(
          new FssaiResult(false, false, true, null, null, null, "MANUAL_REVIEW_REQUIRED"));
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
