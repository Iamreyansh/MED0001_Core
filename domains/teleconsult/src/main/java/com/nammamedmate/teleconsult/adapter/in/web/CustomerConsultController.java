package com.nammamedmate.teleconsult.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.ConsultService;
import com.nammamedmate.teleconsult.application.ConsultService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/consults")
@Tag(name = "Customer teleconsults")
public class CustomerConsultController {

  private final ConsultService service;

  public CustomerConsultController(ConsultService service) {
    this.service = service;
  }

  @PostMapping("/request")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Request a free teleconsult (NOW or scheduled)")
  public ApiResponse<Map<String, Object>> request(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody RequestBodyDto body) {
    RequestBodyDto req =
        body == null ? new RequestBodyDto(null, null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.request(
            principal,
            req.patientName(),
            req.patientPhone(),
            req.slot(),
            req.symptoms(),
            req.medicinesNeedingRx(),
            req.cartId(),
            req.reason()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get own consult status")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PostMapping("/{id}/cancel")
  @Operation(summary = "Cancel a consult before call starts")
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) CancelBody body) {
    String reason = body == null ? null : body.reason();
    return ApiResponse.ok(service.cancel(principal, id, reason));
  }

  @GetMapping
  @Operation(summary = "List customer consults")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    ListResult result = service.list(principal, status, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/rate")
  @Operation(summary = "Rate a completed consult")
  public ApiResponse<Map<String, Object>> rate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) RateBody body) {
    RateBody req = body == null ? new RateBody(null, null) : body;
    return ApiResponse.ok(service.rate(principal, id, req.rating(), req.feedbackText()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RequestBodyDto(
      String patientName,
      String patientPhone,
      String slot,
      List<String> symptoms,
      List<Map<String, Object>> medicinesNeedingRx,
      UUID cartId,
      String reason) {
    public RequestBodyDto {
      symptoms = symptoms == null ? null : List.copyOf(symptoms);
      medicinesNeedingRx = medicinesNeedingRx == null ? null : List.copyOf(medicinesNeedingRx);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CancelBody(String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RateBody(Integer rating, String feedbackText) {}
}
