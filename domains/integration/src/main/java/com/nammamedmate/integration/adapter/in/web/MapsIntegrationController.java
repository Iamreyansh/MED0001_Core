package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.integration.application.MapsService;
import com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/maps")
@Tag(name = "Maps integration (S2S)")
public class MapsIntegrationController {

  private final MapsService maps;
  private final InternalServiceAuth internalAuth;

  public MapsIntegrationController(MapsService maps, InternalServiceAuth internalAuth) {
    this.maps = maps;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/geocode")
  @Operation(summary = "Forward geocode address (internal token)")
  public ApiResponse<Map<String, Object>> geocode(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestHeader(value = "X-Calling-Service", required = false) String callingService,
      @RequestBody(required = false) GeocodeRequest body) {
    internalAuth.require(internalToken);
    GeocodeRequest req = body == null ? new GeocodeRequest(null, null, null) : body;
    return ApiResponse.ok(maps.geocode(req.address(), req.city(), req.pincode(), callingService));
  }

  @PostMapping("/reverse-geocode")
  @Operation(summary = "Reverse geocode lat/lng (internal token)")
  public ApiResponse<Map<String, Object>> reverseGeocode(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestHeader(value = "X-Calling-Service", required = false) String callingService,
      @RequestBody(required = false) ReverseGeocodeRequest body) {
    internalAuth.require(internalToken);
    ReverseGeocodeRequest req = body == null ? new ReverseGeocodeRequest(0, 0) : body;
    return ApiResponse.ok(maps.reverseGeocode(req.lat(), req.lng(), callingService));
  }

  @PostMapping("/distance-matrix")
  @Operation(summary = "Distance matrix (internal token)")
  public ApiResponse<Map<String, Object>> distanceMatrix(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestHeader(value = "X-Calling-Service", required = false) String callingService,
      @RequestBody(required = false) DistanceMatrixRequest body) {
    internalAuth.require(internalToken);
    DistanceMatrixRequest req =
        body == null ? new DistanceMatrixRequest(List.of(), List.of(), "DRIVING") : body;
    return ApiResponse.ok(
        maps.distanceMatrix(
            toLatLngs(req.origins()), toLatLngs(req.destinations()), req.mode(), callingService));
  }

  @PostMapping("/directions")
  @Operation(summary = "Directions route (internal token)")
  public ApiResponse<Map<String, Object>> directions(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestHeader(value = "X-Calling-Service", required = false) String callingService,
      @RequestBody(required = false) DirectionsRequest body) {
    internalAuth.require(internalToken);
    DirectionsRequest req = body == null ? new DirectionsRequest(null, null, "DRIVING") : body;
    LatLng origin =
        req.origin() == null ? null : new LatLng(req.origin().lat(), req.origin().lng());
    LatLng dest =
        req.destination() == null
            ? null
            : new LatLng(req.destination().lat(), req.destination().lng());
    return ApiResponse.ok(maps.directions(origin, dest, req.mode(), callingService));
  }

  @PostMapping("/zone-check")
  @Operation(summary = "Point-in-polygon zone check (local ray-casting, no Google)")
  public ApiResponse<Map<String, Object>> zoneCheck(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestHeader(value = "X-Calling-Service", required = false) String callingService,
      @RequestBody(required = false) ZoneCheckRequest body) {
    internalAuth.require(internalToken);
    ZoneCheckRequest req = body == null ? new ZoneCheckRequest(null, List.of(), null) : body;
    double lat = req.point() == null ? 0 : req.point().lat();
    double lng = req.point() == null ? 0 : req.point().lng();
    double[][] polygon = toPolygon(req.polygonCoordinates());
    return ApiResponse.ok(maps.zoneCheck(lat, lng, polygon, req.zoneId(), callingService));
  }

  private static List<LatLng> toLatLngs(List<PointBody> points) {
    if (points.isEmpty()) {
      return List.of();
    }
    List<LatLng> out = new ArrayList<>(points.size());
    for (PointBody p : points) {
      out.add(new LatLng(p.lat(), p.lng()));
    }
    return out;
  }

  private static double[][] toPolygon(List<List<Double>> coords) {
    if (coords.isEmpty()) {
      return new double[0][];
    }
    double[][] polygon = new double[coords.size()][];
    for (int i = 0; i < coords.size(); i++) {
      List<Double> pair = coords.get(i);
      if (pair.size() < 2) {
        polygon[i] = new double[] {0, 0};
      } else {
        polygon[i] = new double[] {pair.get(0), pair.get(1)};
      }
    }
    return polygon;
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GeocodeRequest(String address, String city, String pincode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReverseGeocodeRequest(double lat, double lng) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PointBody(double lat, double lng) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DistanceMatrixRequest(
      List<PointBody> origins, List<PointBody> destinations, String mode) {
    public DistanceMatrixRequest {
      origins =
          origins == null
              ? List.of()
              : List.copyOf(origins.stream().filter(Objects::nonNull).toList());
      destinations =
          destinations == null
              ? List.of()
              : List.copyOf(destinations.stream().filter(Objects::nonNull).toList());
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DirectionsRequest(PointBody origin, PointBody destination, String mode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ZoneCheckRequest(
      PointBody point, List<List<Double>> polygonCoordinates, String zoneId) {
    public ZoneCheckRequest {
      polygonCoordinates =
          polygonCoordinates == null
              ? List.of()
              : List.copyOf(polygonCoordinates.stream().filter(Objects::nonNull).toList());
    }
  }
}
