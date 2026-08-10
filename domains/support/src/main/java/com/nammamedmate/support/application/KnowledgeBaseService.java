package com.nammamedmate.support.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.port.out.CannedResponseStore;
import com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter;
import com.nammamedmate.support.application.port.out.HelpArticleStore;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.HelpArticle;
import com.nammamedmate.support.domain.TicketCategory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

  private static final Set<AuthRole> CANNED_READ =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final Set<AuthRole> CANNED_WRITE =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<AuthRole> ARTICLE_ADMIN =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);

  private static final int HELP_RATE_LIMIT = 60;
  private static final int HELP_RATE_WINDOW = 60;

  private final CannedResponseStore canned;
  private final HelpArticleStore articles;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public KnowledgeBaseService(
      CannedResponseStore canned, HelpArticleStore articles, RateLimiter rateLimiter, Clock clock) {
    this.canned = canned;
    this.articles = articles;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = Map.copyOf(data);
    }
  }

  public record CannedCreateCommand(
      String title, String category, String body, String shortcutKey) {}

  public record CannedUpdateCommand(
      String title, String category, String body, String shortcutKey) {}

  public record ArticleCreateCommand(
      String title, String category, String contentMarkdown, List<String> tags, Boolean published) {
    public ArticleCreateCommand {
      tags = tags == null ? List.of() : List.copyOf(tags);
    }
  }

  public record ArticleUpdateCommand(
      String title, String category, String contentMarkdown, List<String> tags, Boolean published) {
    public ArticleUpdateCommand {
      tags = tags == null ? null : List.copyOf(tags);
    }
  }

  @Transactional(readOnly = true)
  public ListResult listCanned(
      MedmatePrincipal principal, String category, String q, Integer page, Integer limit) {
    requireRole(principal, CANNED_READ);
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    ListFilter filter =
        new ListFilter(parseCategoryOptional(category), blankToNull(q), pr.offset(), pr.limit());
    List<CannedResponse> rows = canned.list(filter);
    long total = canned.count(filter);
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (CannedResponse r : rows) {
      out.add(toCannedListMap(r));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("canned_responses", out);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional
  public Map<String, Object> createCanned(MedmatePrincipal principal, CannedCreateCommand cmd) {
    requireRole(principal, CANNED_WRITE);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "body is required", 400);
    }
    String title = requireText(cmd.title(), "title");
    TicketCategory cat = parseCategoryRequired(cmd.category());
    String body = requireText(cmd.body(), "body");
    String shortcut = normalizeShortcut(cmd.shortcutKey());
    CannedTemplate.validate(body);
    Instant now = clock.instant();
    CannedResponse row =
        new CannedResponse(
            Ids.newId(), title, cat, body, shortcut, 0, null, principal.subject(), null, now, now);
    canned.insert(row);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("title", row.title());
    data.put("shortcut_key", row.shortcutKey());
    data.put("created_at", row.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> updateCanned(
      MedmatePrincipal principal, UUID id, CannedUpdateCommand cmd) {
    requireRole(principal, CANNED_WRITE);
    CannedResponse existing = requireCanned(id);
    if (cmd == null) {
      cmd = new CannedUpdateCommand(null, null, null, null);
    }
    String body = cmd.body();
    if (body != null) {
      CannedTemplate.validate(body);
    }
    String shortcut = cmd.shortcutKey() == null ? null : normalizeShortcut(cmd.shortcutKey());
    TicketCategory cat =
        cmd.category() == null || cmd.category().isBlank()
            ? null
            : parseCategoryRequired(cmd.category());
    Instant now = clock.instant();
    CannedResponse updated =
        existing.withContent(
            blankToNull(cmd.title()) == null ? null : cmd.title().trim(),
            cat,
            body == null ? null : body,
            shortcut,
            now);
    canned.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> deleteCanned(MedmatePrincipal principal, UUID id) {
    requireRole(principal, CANNED_WRITE);
    CannedResponse existing = requireCanned(id);
    Instant now = clock.instant();
    canned.update(existing.softDeleted(now, now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("deleted", true);
    return data;
  }

  @Transactional(readOnly = true)
  public ListResult listArticlesAdmin(
      MedmatePrincipal principal,
      String category,
      Boolean isPublished,
      Integer page,
      Integer limit) {
    requireRole(principal, ARTICLE_ADMIN);
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    HelpArticleStore.ListFilter filter =
        new HelpArticleStore.ListFilter(
            parseCategoryOptional(category), isPublished, null, false, pr.offset(), pr.limit());
    List<HelpArticle> rows = articles.list(filter);
    long total = articles.count(filter);
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (HelpArticle a : rows) {
      out.add(toAdminArticleMap(a));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("articles", out);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional
  public Map<String, Object> createArticle(MedmatePrincipal principal, ArticleCreateCommand cmd) {
    requireRole(principal, ARTICLE_ADMIN);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "body is required", 400);
    }
    String title = requireText(cmd.title(), "title");
    TicketCategory cat = parseCategoryRequired(cmd.category());
    String content = requireText(cmd.contentMarkdown(), "content_markdown");
    Instant now = clock.instant();
    HelpArticle row =
        new HelpArticle(
            Ids.newId(),
            title,
            cat,
            content,
            cmd.tags(),
            Boolean.TRUE.equals(cmd.published()),
            0,
            0,
            principal.subject(),
            null,
            now,
            now);
    articles.insert(row);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("title", row.title());
    data.put("is_published", row.published());
    data.put("created_at", row.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> updateArticle(
      MedmatePrincipal principal, UUID id, ArticleUpdateCommand cmd) {
    requireRole(principal, ARTICLE_ADMIN);
    HelpArticle existing = requireArticle(id);
    if (cmd == null) {
      cmd = new ArticleUpdateCommand(null, null, null, null, null);
    }
    TicketCategory cat =
        cmd.category() == null || cmd.category().isBlank()
            ? null
            : parseCategoryRequired(cmd.category());
    Instant now = clock.instant();
    HelpArticle updated =
        existing.withContent(
            blankToNull(cmd.title()) == null ? null : cmd.title().trim(),
            cat,
            cmd.contentMarkdown(),
            cmd.tags(),
            cmd.published(),
            now);
    articles.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional(readOnly = true)
  public ListResult publicHelp(String category, String q, String clientIp) {
    rateLimitHelp(clientIp);
    HelpArticleStore.ListFilter filter =
        new HelpArticleStore.ListFilter(
            parseCategoryOptional(category), true, blankToNull(q), true, 0, 100);
    List<HelpArticle> rows = articles.list(filter);
    long total = articles.count(filter);
    List<Map<String, Object>> cats = new ArrayList<>();
    for (HelpArticleStore.CategoryCount c : articles.publishedCategoryCounts(blankToNull(q))) {
      if (category != null && !category.isBlank() && !c.name().equalsIgnoreCase(category.trim())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", c.name());
      row.put("article_count", c.articleCount());
      cats.add(row);
    }
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (HelpArticle a : rows) {
      out.add(toPublicArticleSummary(a));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("categories", cats);
    data.put("articles", out);
    return new ListResult(data, PaginationMeta.of(1, 100, total));
  }

  @Transactional
  public Map<String, Object> readPublicArticle(UUID id, String clientIp) {
    rateLimitHelp(clientIp);
    HelpArticle article =
        articles
            .findById(id)
            .orElseThrow(
                () -> new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404));
    if (!article.published()) {
      throw new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404);
    }
    int views = articles.incrementViewCount(id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", article.id());
    data.put("title", article.title());
    data.put("category", article.category().name());
    data.put("content_markdown", article.contentMarkdown());
    data.put("tags", article.tags());
    data.put("view_count", views > 0 ? views : article.viewCount() + 1);
    data.put("last_updated", article.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> logDeflection(
      MedmatePrincipal principal, UUID articleId, Boolean issueResolved, String clientIp) {
    rateLimitHelp(principal, clientIp);
    if (articleId == null) {
      throw new AppException("VALIDATION_ERROR", "article_id is required", 400);
    }
    HelpArticle article =
        articles
            .findById(articleId)
            .orElseThrow(
                () -> new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404));
    if (!article.published()) {
      throw new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404);
    }
    boolean resolved = Boolean.TRUE.equals(issueResolved);
    boolean logged = false;
    if (resolved) {
      articles.incrementDeflectionCount(articleId);
      logged = true;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("article_id", articleId);
    data.put("issue_resolved", resolved);
    data.put("deflection_logged", logged);
    return data;
  }

  private void rateLimitHelp(String clientIp) {
    rateLimitHelp(null, clientIp);
  }

  private void rateLimitHelp(MedmatePrincipal principal, String clientIp) {
    String key =
        principal != null
            ? "support:help:user:" + principal.subject()
            : "support:help:ip:"
                + (clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp.trim());
    if (!rateLimiter.tryAcquire(key, HELP_RATE_LIMIT, HELP_RATE_WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private CannedResponse requireCanned(UUID id) {
    if (id == null) {
      throw new AppException("CANNED_RESPONSE_NOT_FOUND", "Canned response not found", 404);
    }
    return canned
        .findById(id)
        .orElseThrow(
            () -> new AppException("CANNED_RESPONSE_NOT_FOUND", "Canned response not found", 404));
  }

  private HelpArticle requireArticle(UUID id) {
    if (id == null) {
      throw new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404);
    }
    return articles
        .findById(id)
        .orElseThrow(
            () -> new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404));
  }

  private static Map<String, Object> toCannedListMap(CannedResponse r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("title", r.title());
    m.put("category", r.category().name());
    m.put("body", r.body());
    m.put("shortcut_key", r.shortcutKey());
    m.put("copy_count", r.copyCount());
    m.put("last_used_at", r.lastUsedAt());
    return m;
  }

  private static Map<String, Object> toAdminArticleMap(HelpArticle a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.id());
    m.put("title", a.title());
    m.put("category", a.category().name());
    m.put("view_count", a.viewCount());
    m.put("deflection_count", a.deflectionCount());
    m.put("is_published", a.published());
    m.put("last_updated", a.updatedAt());
    return m;
  }

  private static Map<String, Object> toPublicArticleSummary(HelpArticle a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.id());
    m.put("title", a.title());
    m.put("category", a.category().name());
    m.put("summary", summary(a.contentMarkdown()));
    m.put("tags", a.tags());
    return m;
  }

  private static String summary(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    String plain =
        markdown.replaceAll("^#+\\s*", "").replaceAll("\\n+", " ").replaceAll("\\s+", " ").trim();
    return plain.length() <= 120 ? plain : plain.substring(0, 117) + "...";
  }

  private static String normalizeShortcut(String raw) {
    String s = requireText(raw, "shortcut_key").trim();
    if (!s.startsWith("/")) {
      s = "/" + s;
    }
    if (s.length() > 50) {
      throw new AppException("VALIDATION_ERROR", "shortcut_key too long", 400);
    }
    return s.toLowerCase(Locale.ROOT);
  }

  private static TicketCategory parseCategoryRequired(String raw) {
    TicketCategory c = parseCategoryOptional(raw);
    if (c == null) {
      throw new AppException("VALIDATION_ERROR", "category is required", 400);
    }
    return c;
  }

  private static TicketCategory parseCategoryOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return TicketCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid category", 400);
    }
  }

  private static String requireText(String v, String field) {
    if (v == null || v.isBlank()) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    return v.trim();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static void requireRole(MedmatePrincipal principal, Set<AuthRole> roles) {
    if (principal == null || !roles.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
