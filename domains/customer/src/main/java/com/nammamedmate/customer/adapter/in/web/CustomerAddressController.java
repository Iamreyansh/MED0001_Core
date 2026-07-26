package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.CustomerAddressService;
import com.nammamedmate.customer.application.CustomerAddressService.AddressCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/addresses")
@Tag(name = "Customer addresses")
public class CustomerAddressController {

  private final CustomerAddressService service;

  public CustomerAddressController(CustomerAddressService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List saved delivery addresses")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.list(principal));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a delivery address")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AddressRequest body) {
    return ApiResponse.ok(service.create(principal, toCommand(body)));
  }

  @PutMapping("/{id}")
  @Operation(summary = "Update a delivery address")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) AddressRequest body) {
    return ApiResponse.ok(service.update(principal, id, toCommand(body)));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a delivery address")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.delete(principal, id));
  }

  @PatchMapping("/{id}/set-default")
  @Operation(summary = "Set default delivery address")
  public ApiResponse<Map<String, Object>> setDefault(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.setDefault(principal, id));
  }

  @PostMapping("/geocode")
  @Operation(summary = "Reverse-geocode coordinates to a suggested address")
  public ApiResponse<Map<String, Object>> geocode(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) GeocodeRequest body) {
    Double lat = body == null ? null : body.latitude();
    Double lng = body == null ? null : body.longitude();
    return ApiResponse.ok(service.geocode(principal, lat, lng));
  }

  private static AddressCommand toCommand(AddressRequest body) {
    if (body == null) {
      return null;
    }
    return new AddressCommand(
        body.label(),
        body.flatBuilding(),
        body.areaLocality(),
        body.city(),
        body.state(),
        body.pincode(),
        body.latitude(),
        body.longitude(),
        body.isDefault());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddressRequest(
      String label,
      String flatBuilding,
      String areaLocality,
      String city,
      String state,
      String pincode,
      Double latitude,
      Double longitude,
      Boolean isDefault) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GeocodeRequest(Double latitude, Double longitude) {}
}
