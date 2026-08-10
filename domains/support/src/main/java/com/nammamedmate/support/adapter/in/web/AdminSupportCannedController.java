package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.KnowledgeBaseService;
import com.nammamedmate.support.application.KnowledgeBaseService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support/canned-responses")
@Tag(name = "Admin support canned responses")
public class AdminSupportCannedController {

  private final KnowledgeBaseService kb;

  public AdminSupportCannedController(KnowledgeBaseService kb) {
    this.kb = kb;
  }

  @GetMapping
  @Operation(summary = "List canned responses (shortcut / search)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ListResult result = kb.listCanned(principal, category, q, page, limit);
    return ResponseEntity.ok(ApiResponse.ok(result.data(), result.meta()));
  }

  @PostMapping
  @Operation(summary = "Create canned response (super|ops)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateRequest body) {
    CreateRequest req = body == null ? new CreateRequest(null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                kb.createCanned(
                    principal,
                    new KnowledgeBaseService.CannedCreateCommand(
                        req.title(), req.category(), req.body(), req.shortcutKey()))));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update canned response (super|ops)")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        kb.updateCanned(
            principal,
            id,
            new KnowledgeBaseService.CannedUpdateCommand(
                req.title(), req.category(), req.body(), req.shortcutKey())));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete canned response (super|ops)")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(kb.deleteCanned(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(String title, String category, String body, String shortcutKey) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(String title, String category, String body, String shortcutKey) {}
}
