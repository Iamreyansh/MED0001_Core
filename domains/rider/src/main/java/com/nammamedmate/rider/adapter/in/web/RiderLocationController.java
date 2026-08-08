package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderLocationService;
import com.nammamedmate.rider.application.RiderLocationService.GpsPoint;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/location")
@Tag(name = "Rider GPS location")
public class RiderLocationController {

  private final RiderLocationService service;

  public RiderLocationController(RiderLocationService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(summary = "Post batch GPS points (≤60)")
  public ApiResponse<Map<String, Object>> postLocation(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody LocationBatchRequest body) {
    List<GpsPoint> points =
        body == null || body.points() == null
            ? List.of()
            : body.points().stream()
                .map(
                    p ->
                        new GpsPoint(
                            p.lat(), p.lng(), p.accuracy(), p.speed(), p.heading(), p.timestamp()))
                .toList();
    return ApiResponse.ok(service.ingest(principal, points));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LocationBatchRequest(List<PointDto> points) {
    public LocationBatchRequest {
      points = points == null ? null : List.copyOf(points);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PointDto(
      Double lat, Double lng, Double accuracy, Double speed, Double heading, Instant timestamp) {}
}
