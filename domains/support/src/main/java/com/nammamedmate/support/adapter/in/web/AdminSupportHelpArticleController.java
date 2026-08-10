package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.KnowledgeBaseService;
import com.nammamedmate.support.application.KnowledgeBaseService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support/help-articles")
@Tag(name = "Admin support help articles")
public class AdminSupportHelpArticleController {

  private final KnowledgeBaseService kb;

  public AdminSupportHelpArticleController(KnowledgeBaseService kb) {
    this.kb = kb;
  }

  @GetMapping
  @Operation(summary = "List help articles sorted by deflection_count desc")
  public ResponseEntity<ApiResponse<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String category,
      @RequestParam(name = "is_published", required = false) Boolean isPublished,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ListResult result = kb.listArticlesAdmin(principal, category, isPublished, page, limit);
    return ResponseEntity.ok(ApiResponse.ok(result.data(), result.meta()));
  }

  @PostMapping
  @Operation(summary = "Create help article")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateRequest body) {
    CreateRequest req = body == null ? new CreateRequest(null, null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                kb.createArticle(
                    principal,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        req.title(),
                        req.category(),
                        req.contentMarkdown(),
                        req.tags(),
                        req.isPublished()))));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update help article")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        kb.updateArticle(
            principal,
            id,
            new KnowledgeBaseService.ArticleUpdateCommand(
                req.title(),
                req.category(),
                req.contentMarkdown(),
                req.tags(),
                req.isPublished())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(
      String title,
      String category,
      String contentMarkdown,
      List<String> tags,
      Boolean isPublished) {
    public CreateRequest {
      tags = tags == null ? null : List.copyOf(tags);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(
      String title,
      String category,
      String contentMarkdown,
      List<String> tags,
      Boolean isPublished) {
    public UpdateRequest {
      tags = tags == null ? null : List.copyOf(tags);
    }
  }
}
