package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.SupplierRtvService;
import com.nammamedmate.inventory.application.SupplierRtvService.RtvLine;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/purchases")
@Tag(name = "Pharmacy purchases / RTV")
public class PharmacyRtvController {

  private final SupplierRtvService rtv;

  public PharmacyRtvController(SupplierRtvService rtv) {
    this.rtv = rtv;
  }

  @PostMapping("/{grnId}/rtv")
  @Operation(summary = "Return stocked GRN lines to the vendor")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID grnId,
      @RequestBody(required = false) RtvRequest body) {
    RtvRequest req = body == null ? new RtvRequest(null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(rtv.create(principal, grnId, req.reason(), req.items())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RtvRequest(String reason, List<RtvLine> items) {}
}
