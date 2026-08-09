package com.nammamedmate.integration.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live Google Maps Platform client. Uses segregated keys per capability; blank key for a call falls
 * back to {@link StubMapsClient}.
 */
public final class LiveMapsClient implements MapsClientPort {

  private static final String GEOCODE_URL =
      "https://maps.googleapis.com/maps/api/geocode/json?address=%s&key=%s";
  private static final String REVERSE_URL =
      "https://maps.googleapis.com/maps/api/geocode/json?latlng=%s,%s&key=%s";
  private static final String MATRIX_URL =
      "https://maps.googleapis.com/maps/api/distancematrix/json?origins=%s&destinations=%s&mode=%s&key=%s";
  private static final String DIRECTIONS_URL =
      "https://maps.googleapis.com/maps/api/directions/json?origin=%s&destination=%s&mode=%s&departure_time=now&key=%s";

  private final String geocodeKey;
  private final String distanceMatrixKey;
  private final String directionsKey;
  private final ObjectMapper mapper;
  private final Function<URI, String> httpGet;
  private final StubMapsClient stub = new StubMapsClient();

  public LiveMapsClient(
      String geocodeKey,
      String distanceMatrixKey,
      String directionsKey,
      ObjectMapper mapper,
      Function<URI, String> httpGet) {
    this.geocodeKey = geocodeKey == null ? "" : geocodeKey.trim();
    this.distanceMatrixKey = distanceMatrixKey == null ? "" : distanceMatrixKey.trim();
    this.directionsKey = directionsKey == null ? "" : directionsKey.trim();
    this.mapper = mapper;
    this.httpGet = httpGet;
  }

  @Override
  public GeocodeResult geocode(String addressQuery) {
    if (geocodeKey.isEmpty()) {
      return stub.geocode(addressQuery);
    }
    String body = get(GEOCODE_URL.formatted(enc(addressQuery), enc(geocodeKey)));
    try {
      JsonNode root = mapper.readTree(body);
      String status = text(root, "status");
      if ("ZERO_RESULTS".equals(status)) {
        return new GeocodeResult(0, 0, "", "", "", "ZERO_RESULTS");
      }
      if (!"OK".equals(status)) {
        throw mapsError(status);
      }
      JsonNode results = root.path("results");
      if (!results.isArray() || results.isEmpty()) {
        return new GeocodeResult(0, 0, "", "", "", "ZERO_RESULTS");
      }
      if (results.size() > 1 && lowConfidence(results.get(0))) {
        throw new AppException(
            "GEOCODE_AMBIGUOUS",
            "Multiple geocode results with low confidence; use a more specific address",
            422);
      }
      JsonNode first = results.get(0);
      JsonNode loc = first.path("geometry").path("location");
      String accuracy = text(first.path("geometry"), "location_type");
      return new GeocodeResult(
          loc.path("lat").asDouble(),
          loc.path("lng").asDouble(),
          text(first, "formatted_address"),
          text(first, "place_id"),
          accuracy.isEmpty() ? "APPROXIMATE" : accuracy,
          "OK");
    } catch (AppException e) {
      throw e;
    } catch (IOException e) {
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps API returned invalid JSON", 503);
    }
  }

  @Override
  public ReverseGeocodeResult reverseGeocode(double lat, double lng) {
    if (geocodeKey.isEmpty()) {
      return stub.reverseGeocode(lat, lng);
    }
    String body =
        get(
            REVERSE_URL.formatted(
                enc(Double.toString(lat)), enc(Double.toString(lng)), enc(geocodeKey)));
    try {
      JsonNode root = mapper.readTree(body);
      String status = text(root, "status");
      if ("ZERO_RESULTS".equals(status)) {
        return new ReverseGeocodeResult("", "", "", "", "", "", "ZERO_RESULTS");
      }
      if (!"OK".equals(status)) {
        throw mapsError(status);
      }
      JsonNode results = root.path("results");
      if (!results.isArray() || results.isEmpty()) {
        return new ReverseGeocodeResult("", "", "", "", "", "", "ZERO_RESULTS");
      }
      JsonNode first = results.get(0);
      String area = "";
      String city = "";
      String state = "";
      String pincode = "";
      for (JsonNode component : first.path("address_components")) {
        String longName = text(component, "long_name");
        for (JsonNode type : component.path("types")) {
          String t = type.asText();
          if (("sublocality".equals(t)
                  || "sublocality_level_1".equals(t)
                  || "neighborhood".equals(t)
                  || "route".equals(t))
              && area.isEmpty()) {
            area = longName;
          } else if ("locality".equals(t)) {
            city = longName;
          } else if ("administrative_area_level_1".equals(t)) {
            state = longName;
          } else if ("postal_code".equals(t)) {
            pincode = longName;
          }
        }
      }
      return new ReverseGeocodeResult(
          text(first, "formatted_address"),
          area,
          city,
          state,
          pincode,
          text(first, "place_id"),
          "OK");
    } catch (AppException e) {
      throw e;
    } catch (IOException e) {
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps API returned invalid JSON", 503);
    }
  }

  @Override
  public List<MatrixCell> distanceMatrix(
      List<LatLng> origins, List<LatLng> destinations, String mode) {
    if (distanceMatrixKey.isEmpty()) {
      return stub.distanceMatrix(origins, destinations, mode);
    }
    String originStr =
        origins.stream().map(o -> o.lat() + "," + o.lng()).collect(Collectors.joining("|"));
    String destStr =
        destinations.stream().map(d -> d.lat() + "," + d.lng()).collect(Collectors.joining("|"));
    String travelMode = mode == null || mode.isBlank() ? "driving" : mode.toLowerCase(Locale.ROOT);
    String body =
        get(
            MATRIX_URL.formatted(
                enc(originStr), enc(destStr), enc(travelMode), enc(distanceMatrixKey)));
    try {
      JsonNode root = mapper.readTree(body);
      String status = text(root, "status");
      if (!"OK".equals(status)) {
        throw mapsError(status);
      }
      List<MatrixCell> cells = new ArrayList<>();
      JsonNode rows = root.path("rows");
      for (int i = 0; i < rows.size(); i++) {
        JsonNode elements = rows.get(i).path("elements");
        for (int j = 0; j < elements.size(); j++) {
          JsonNode el = elements.get(j);
          String elStatus = text(el, "status");
          if (!"OK".equals(elStatus)) {
            cells.add(new MatrixCell(i, j, 0, 0, elStatus));
            continue;
          }
          cells.add(
              new MatrixCell(
                  i,
                  j,
                  el.path("distance").path("value").asInt(),
                  el.path("duration").path("value").asInt(),
                  "OK"));
        }
      }
      return cells;
    } catch (AppException e) {
      throw e;
    } catch (IOException e) {
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps API returned invalid JSON", 503);
    }
  }

  @Override
  public DirectionsResult directions(LatLng origin, LatLng destination, String mode) {
    if (directionsKey.isEmpty()) {
      return stub.directions(origin, destination, mode);
    }
    String travelMode = mode == null || mode.isBlank() ? "driving" : mode.toLowerCase(Locale.ROOT);
    String body =
        get(
            DIRECTIONS_URL.formatted(
                enc(origin.lat() + "," + origin.lng()),
                enc(destination.lat() + "," + destination.lng()),
                enc(travelMode),
                enc(directionsKey)));
    try {
      JsonNode root = mapper.readTree(body);
      String status = text(root, "status");
      if (!"OK".equals(status)) {
        throw mapsError(status);
      }
      JsonNode routes = root.path("routes");
      if (!routes.isArray() || routes.isEmpty()) {
        throw new AppException("GEOCODE_NO_RESULTS", "Directions returned no routes", 422);
      }
      JsonNode route = routes.get(0);
      JsonNode leg = route.path("legs").path(0);
      int duration = leg.path("duration").path("value").asInt();
      int inTraffic =
          leg.has("duration_in_traffic")
              ? leg.path("duration_in_traffic").path("value").asInt()
              : duration;
      List<DirectionStep> steps = new ArrayList<>();
      for (JsonNode step : leg.path("steps")) {
        steps.add(
            new DirectionStep(
                text(step, "html_instructions").replaceAll("<[^>]+>", ""),
                step.path("distance").path("value").asInt(),
                step.path("duration").path("value").asInt()));
      }
      return new DirectionsResult(
          text(route.path("overview_polyline"), "points"),
          leg.path("distance").path("value").asInt(),
          duration,
          inTraffic,
          steps,
          "OK");
    } catch (AppException e) {
      throw e;
    } catch (IOException e) {
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps API returned invalid JSON", 503);
    }
  }

  private String get(String url) {
    try {
      return httpGet.apply(URI.create(url));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("MAPS_API_UNAVAILABLE", "Google Maps API unreachable", 503);
    }
  }

  private static boolean lowConfidence(JsonNode result) {
    String locType = text(result.path("geometry"), "location_type");
    return "APPROXIMATE".equals(locType) || "GEOMETRIC_CENTER".equals(locType);
  }

  private static AppException mapsError(String status) {
    if ("OVER_DAILY_LIMIT".equals(status) || "OVER_QUERY_LIMIT".equals(status)) {
      return new AppException(
          "MAPS_API_UNAVAILABLE",
          "Google Maps API limit: " + status + "; use last known address as fallback",
          503);
    }
    if ("ZERO_RESULTS".equals(status)) {
      return new AppException(
          "GEOCODE_NO_RESULTS", "Google returned ZERO_RESULTS; use last known address", 422);
    }
    return new AppException("MAPS_API_UNAVAILABLE", "Google Maps API status: " + status, 503);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
