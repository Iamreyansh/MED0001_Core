package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.DrugRegistryClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Live state drug-registry client. Credentials never logged. */
public final class LiveDrugRegistryClient implements DrugRegistryClientPort {

  private final String apiKey;
  private final String baseUrl;
  private final ObjectMapper mapper;
  private final Function<URI, String> httpGet;

  public LiveDrugRegistryClient(
      String apiKey, String baseUrl, ObjectMapper mapper, Function<URI, String> httpGet) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public DrugLicenceResult verify(String licenceNumber, String state, String licenceType) {
    try {
      String q = "licence=" + enc(licenceNumber) + "&state=" + enc(state) + "&key=" + enc(apiKey);
      JsonNode root = mapper.readTree(httpGet.apply(URI.create(baseUrl + "/licence?" + q)));
      if (root.path("async").asBoolean(false) || "PENDING".equals(root.path("status").asText())) {
        return new DrugLicenceResult(
            true, false, false, false, null, null, null, List.of(), state, licenceType, "PENDING");
      }
      if (root.path("manual_review").asBoolean(false)) {
        return new DrugLicenceResult(
            false,
            true,
            false,
            false,
            null,
            null,
            null,
            List.of(),
            state,
            licenceType,
            "MANUAL_REVIEW_REQUIRED");
      }
      List<String> drugs = new ArrayList<>();
      root.path("drugs_permitted").forEach(n -> drugs.add(n.asText()));
      LocalDate expiry = parseDate(root.path("expiry_date").asText(null));
      boolean expired = expiry != null && expiry.isBefore(LocalDate.now());
      String status = expired ? "EXPIRED" : root.path("status").asText("ACTIVE");
      return new DrugLicenceResult(
          false,
          false,
          true,
          expired ? false : root.path("valid").asBoolean(true),
          text(root, "holder_name"),
          parseDate(root.path("issued_date").asText(null)),
          expiry,
          List.copyOf(drugs),
          state,
          licenceType,
          status);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      return new DrugLicenceResult(
          false,
          true,
          false,
          false,
          null,
          null,
          null,
          List.of(),
          state,
          licenceType,
          "MANUAL_REVIEW_REQUIRED");
    }
  }

  private static String enc(String v) {
    return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
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
