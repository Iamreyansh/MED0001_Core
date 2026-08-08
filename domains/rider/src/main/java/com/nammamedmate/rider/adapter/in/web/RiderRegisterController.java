package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider")
@Tag(name = "Rider registration")
public class RiderRegisterController {

  private final RiderRegistrationService service;

  public RiderRegisterController(RiderRegistrationService service) {
    this.service = service;
  }

  @PostMapping("/register")
  @Operation(summary = "Register a new rider account")
  public ResponseEntity<ApiResponse<Map<String, Object>>> register(
      @RequestBody RegisterRequest request) {
    Map<String, Object> data =
        service.register(
            request.name(),
            request.phone(),
            request.email(),
            request.vehicleType(),
            request.vehiclePlateNumber(),
            request.preferredZoneId());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RegisterRequest(
      @NotBlank String name,
      @NotBlank String phone,
      String email,
      @NotBlank String vehicleType,
      @NotBlank String vehiclePlateNumber,
      UUID preferredZoneId) {}
}
