package com.nammamedmate.teleconsult.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.TeleconsultDoctorService;
import com.nammamedmate.teleconsult.application.TeleconsultDoctorService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/teleconsult/doctors")
@Tag(name = "Admin teleconsult doctors")
public class AdminTeleconsultDoctorController {

  private final TeleconsultDoctorService service;

  public AdminTeleconsultDoctorController(TeleconsultDoctorService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List teleconsult doctor roster")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "is_available", required = false) Boolean isAvailable,
      @RequestParam(name = "specialty", required = false) String specialty,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    ListResult result = service.list(principal, isAvailable, specialty, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add teleconsult doctor")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateRequest body) {
    CreateRequest req =
        body == null
            ? new CreateRequest(null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        service.create(
            principal,
            req.name(),
            req.qualification(),
            req.registrationNo(),
            req.specialty(),
            req.languagesSpoken(),
            req.yearsExperience(),
            req.avatarUrl(),
            req.bio(),
            req.internalPhone()));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update teleconsult doctor profile")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.update(principal, id, body));
  }

  @PatchMapping("/{id}/availability")
  @Operation(summary = "Toggle teleconsult doctor availability")
  public ApiResponse<Map<String, Object>> availability(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) AvailabilityRequest body) {
    Boolean available = body == null ? null : body.isAvailable();
    return ApiResponse.ok(service.setAvailability(principal, id, available));
  }

  @GetMapping("/{id}/stats")
  @Operation(summary = "Get teleconsult doctor consult stats")
  public ApiResponse<Map<String, Object>> stats(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestParam(name = "period", required = false) String period) {
    return ApiResponse.ok(service.stats(principal, id, period));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(
      String name,
      String qualification,
      String registrationNo,
      String specialty,
      List<String> languagesSpoken,
      Integer yearsExperience,
      String avatarUrl,
      String bio,
      String internalPhone) {
    public CreateRequest {
      languagesSpoken = languagesSpoken == null ? null : List.copyOf(languagesSpoken);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AvailabilityRequest(Boolean isAvailable) {}
}
