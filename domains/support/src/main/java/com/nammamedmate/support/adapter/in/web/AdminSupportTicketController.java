package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.TicketService;
import com.nammamedmate.support.application.TicketService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support/tickets")
@Tag(name = "Admin support tickets")
public class AdminSupportTicketController {

  private final TicketService tickets;

  public AdminSupportTicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @GetMapping
  @Operation(summary = "List support tickets with chips; export=true returns CSV")
  public ResponseEntity<?> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String q,
      @RequestParam(name = "assigned_agent_id", required = false) UUID assignedAgentId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Boolean export) {
    ListResult result =
        tickets.listAdmin(
            principal,
            status,
            priority,
            category,
            channel,
            q,
            assignedAgentId,
            page,
            limit,
            export);
    if (Boolean.TRUE.equals(export)) {
      byte[] csv = tickets.exportCsvBytes(result);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"support-tickets.csv\"")
          .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
          .body(csv);
    }
    if (result.meta() == null) {
      return ResponseEntity.ok(ApiResponse.ok(result.data()));
    }
    return ResponseEntity.ok(ApiResponse.ok(result.data(), result.meta()));
  }

  @PostMapping
  @Operation(summary = "Admin create ticket on behalf of customer")
  public ResponseEntity<ApiResponse<Map<String, Object>>> createOnBehalf(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AdminCreateRequest body) {
    AdminCreateRequest req =
        body == null ? new AdminCreateRequest(null, null, null, null, null, null) : body;
    Map<String, Object> data =
        tickets.createOnBehalf(
            principal,
            new TicketService.AdminCreateCommand(
                req.customerId(),
                req.category(),
                req.subject(),
                req.description(),
                req.orderId(),
                req.channel()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdminCreateRequest(
      UUID customerId,
      String category,
      String subject,
      String description,
      UUID orderId,
      String channel) {}
}
