package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.DisputeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support/disputes")
@Tag(name = "Support disputes")
public class SupportDisputeController {

  private final DisputeService disputes;

  public SupportDisputeController(DisputeService disputes) {
    this.disputes = disputes;
  }

  @PostMapping
  @Operation(summary = "Create order dispute")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateDisputeRequest body) {
    CreateDisputeRequest req =
        body == null ? new CreateDisputeRequest(null, null, null, null) : body;
    Map<String, Object> data =
        disputes.create(
            principal,
            new DisputeService.CreateCommand(
                req.orderId(), req.disputeType(), req.description(), req.evidenceUrls()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateDisputeRequest(
      UUID orderId, String disputeType, String description, List<String> evidenceUrls) {
    public CreateDisputeRequest {
      evidenceUrls = evidenceUrls == null ? null : List.copyOf(evidenceUrls);
    }
  }
}
