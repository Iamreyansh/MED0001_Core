package com.nammamedmate.customer.adapter.out.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.kernel.error.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Google Maps Geocoding API reverse-geocode client. */
public final class GoogleMapsGeocodeClient implements GeocodePort {

  private static final String ENDPOINT =
      "https://maps.googleapis.com/maps/api/geocode/json?latlng=%s,%s&key=%s";

  private final String apiKey;
  private final ObjectMapper mapper;
  private final Function<URI, String> httpGet;

  public GoogleMapsGeocodeClient(
      String apiKey, ObjectMapper mapper, Function<URI, String> httpGet) {
    this.apiKey = apiKey;
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public SuggestedAddress reverseGeocode(double latitude, double longitude) {
    URI uri =
        URI.create(
            ENDPOINT.formatted(
                encode(Double.toString(latitude)),
                encode(Double.toString(longitude)),
                encode(apiKey)));
    String body;
    try {
      body = httpGet.apply(uri);
    } catch (RuntimeException ex) {
      throw new AppException("GEOCODE_SERVICE_ERROR", "Google Maps API request failed", 502);
    }
    try {
      return parse(body, latitude, longitude);
    } catch (AppException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AppException("GEOCODE_SERVICE_ERROR", "Google Maps API returned an error", 502);
    }
  }

  SuggestedAddress parse(String body, double latitude, double longitude) throws IOException {
    JsonNode root = mapper.readTree(body);
    String status = text(root, "status");
    if (!"OK".equals(status) && !"ZERO_RESULTS".equals(status)) {
      throw new AppException("GEOCODE_SERVICE_ERROR", "Google Maps API returned an error", 502);
    }
    JsonNode results = root.path("results");
    if (!results.isArray() || results.isEmpty()) {
      throw new AppException("GEOCODE_SERVICE_ERROR", "Google Maps API returned an error", 502);
    }
    JsonNode first = results.get(0);
    String formatted = text(first, "formatted_address");
    String area = "";
    String city = "";
    String state = "";
    String pincode = "";
    for (JsonNode component : first.path("address_components")) {
      String longName = text(component, "long_name");
      for (JsonNode type : component.path("types")) {
        String t = type.asText();
        if ("sublocality".equals(t)
            || "sublocality_level_1".equals(t)
            || "neighborhood".equals(t)
            || "route".equals(t)) {
          if (area.isEmpty()) {
            area = longName;
          }
        } else if ("locality".equals(t)) {
          city = longName;
        } else if ("administrative_area_level_1".equals(t)) {
          state = longName;
        } else if ("postal_code".equals(t)) {
          pincode = longName;
        }
      }
    }
    if (city.isEmpty()) {
      city = text(first.path("address_components").path(0), "long_name");
    }
    return new SuggestedAddress("", area, city, state, pincode, formatted, latitude, longitude);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
