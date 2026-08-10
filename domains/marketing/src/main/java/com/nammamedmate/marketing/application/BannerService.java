package com.nammamedmate.marketing.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.BannerImageValidatorPort;
import com.nammamedmate.marketing.application.port.out.BannerStore;
import com.nammamedmate.marketing.application.port.out.ImpressionThrottlePort;
import com.nammamedmate.marketing.application.port.out.MarketingAuditPort;
import com.nammamedmate.marketing.domain.Banner;
import com.nammamedmate.marketing.domain.BannerLinkType;
import com.nammamedmate.marketing.domain.BannerPlacement;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BannerService {

  private final BannerStore store;
  private final BannerImageValidatorPort images;
  private final ImpressionThrottlePort throttle;
  private final MarketingAuditPort audit;
  private final Clock clock;

  public BannerService(
      BannerStore store,
      BannerImageValidatorPort images,
      ImpressionThrottlePort throttle,
      MarketingAuditPort audit,
      Clock clock) {
    this.store = store;
    this.images = images;
    this.throttle = throttle;
    this.audit = audit;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {}

  public record CreateCommand(
      String headline,
      String subText,
      String imageUrl,
      String placement,
      String linkType,
      String linkValue,
      String themeColor,
      Boolean live,
      Instant validFrom,
      Instant validUntil,
      Integer priority) {}

  public record PatchCommand(
      String headline,
      String subText,
      String imageUrl,
      String placement,
      String linkType,
      String linkValue,
      String themeColor,
      Boolean live,
      Instant validFrom,
      Instant validUntil,
      Integer priority) {}

  public record ReorderItem(UUID id, Integer priority) {}

  @Transactional(readOnly = true)
  public PagedResult listAdmin(
      MedmatePrincipal principal, String placement, Boolean isLive, Integer page, Integer limit) {
    requireAdminRead(principal);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    BannerPlacement pl = parsePlacementFilter(placement);
    long total = store.count(pl, isLive);
    List<Banner> rows = store.list(pl, isLive, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Banner b : rows) {
      items.add(toAdminItem(b));
    }
    return new PagedResult(Map.of("banners", items), PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireAdminWrite(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "headline is required", 422);
    }
    if (cmd.headline() == null || cmd.headline().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "headline is required", 422);
    }
    if (cmd.headline().length() > 120) {
      throw new AppException("VALIDATION_ERROR", "headline max 120 chars", 422);
    }
    if (cmd.subText() != null && cmd.subText().length() > 200) {
      throw new AppException("VALIDATION_ERROR", "sub_text max 200 chars", 422);
    }
    if (cmd.imageUrl() == null || cmd.imageUrl().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "image_url is required", 422);
    }
    if (cmd.linkValue() == null || cmd.linkValue().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "link_value is required", 422);
    }
    BannerPlacement placement = parsePlacementRequired(cmd.placement());
    BannerLinkType linkType = parseLinkType(cmd.linkType());
    Instant from = cmd.validFrom() == null ? clock.instant() : cmd.validFrom();
    Instant until = cmd.validUntil();
    if (until == null) {
      throw new AppException("VALIDATION_ERROR", "valid_until is required", 422);
    }
    if (from.isAfter(until)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_from must be before valid_until", 422);
    }
    images.validate(cmd.imageUrl().trim());
    Instant now = clock.instant();
    boolean live = cmd.live() == null || Boolean.TRUE.equals(cmd.live());
    int priority = cmd.priority() == null ? 100 : cmd.priority();
    Banner created =
        store.insert(
            new Banner(
                Ids.newId(),
                cmd.headline().trim(),
                blankToNull(cmd.subText()),
                cmd.imageUrl().trim(),
                placement,
                linkType,
                cmd.linkValue().trim(),
                blankToNull(cmd.themeColor()),
                live,
                from,
                until,
                priority,
                0L,
                0L,
                principal.subject(),
                now,
                now));
    auditSafe(principal, created.id(), "CREATE", null, toAuditMap(created));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", created.id());
    data.put("headline", created.headline());
    data.put("placement", created.placement().name());
    data.put("status", created.statusLabel());
    data.put("created_at", created.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> patch(MedmatePrincipal principal, UUID id, PatchCommand cmd) {
    requireAdminWrite(principal);
    Banner existing = requireBanner(id);
    PatchCommand c =
        cmd == null
            ? new PatchCommand(null, null, null, null, null, null, null, null, null, null, null)
            : cmd;
    String headline = c.headline() != null ? c.headline().trim() : existing.headline();
    if (headline.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "headline is required", 422);
    }
    if (headline.length() > 120) {
      throw new AppException("VALIDATION_ERROR", "headline max 120 chars", 422);
    }
    String subText = c.subText() != null ? blankToNull(c.subText()) : existing.subText();
    if (subText != null && subText.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "sub_text max 200 chars", 422);
    }
    String imageUrl = c.imageUrl() != null ? c.imageUrl().trim() : existing.imageUrl();
    if (c.imageUrl() != null) {
      images.validate(imageUrl);
    }
    BannerPlacement placement =
        c.placement() != null ? parsePlacementRequired(c.placement()) : existing.placement();
    BannerLinkType linkType =
        c.linkType() != null ? parseLinkType(c.linkType()) : existing.linkType();
    String linkValue = c.linkValue() != null ? c.linkValue().trim() : existing.linkValue();
    if (linkValue.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "link_value is required", 422);
    }
    String themeColor =
        c.themeColor() != null ? blankToNull(c.themeColor()) : existing.themeColor();
    boolean live = c.live() != null ? Boolean.TRUE.equals(c.live()) : existing.live();
    Instant from = c.validFrom() != null ? c.validFrom() : existing.validFrom();
    Instant until = c.validUntil() != null ? c.validUntil() : existing.validUntil();
    if (from.isAfter(until)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_from must be before valid_until", 422);
    }
    int priority = c.priority() != null ? c.priority() : existing.priority();
    Instant now = clock.instant();
    Banner updated =
        new Banner(
            existing.id(),
            headline,
            subText,
            imageUrl,
            placement,
            linkType,
            linkValue,
            themeColor,
            live,
            from,
            until,
            priority,
            existing.impressions(),
            existing.clicks(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    auditSafe(principal, id, "UPDATE", toAuditMap(existing), toAuditMap(updated));
    return Map.of("id", id, "updated_at", now);
  }

  @Transactional
  public Map<String, Object> toggle(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    Banner existing = requireBanner(id);
    Instant now = clock.instant();
    Banner updated =
        new Banner(
            existing.id(),
            existing.headline(),
            existing.subText(),
            existing.imageUrl(),
            existing.placement(),
            existing.linkType(),
            existing.linkValue(),
            existing.themeColor(),
            !existing.live(),
            existing.validFrom(),
            existing.validUntil(),
            existing.priority(),
            existing.impressions(),
            existing.clicks(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    auditSafe(principal, id, "TOGGLE", toAuditMap(existing), toAuditMap(updated));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("is_live", updated.live());
    data.put("toggled_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id) {
    requireAdminSuper(principal);
    Banner existing = requireBanner(id);
    store.hardDelete(id);
    auditSafe(principal, id, "DELETE", toAuditMap(existing), Map.of("deleted", true));
    return Map.of("id", id, "deleted", true);
  }

  @Transactional
  public Map<String, Object> reorder(MedmatePrincipal principal, List<ReorderItem> items) {
    requireAdminWrite(principal);
    if (items == null || items.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "items is required", 422);
    }
    List<UUID> ids = new ArrayList<>();
    List<BannerStore.ReorderItem> storeItems = new ArrayList<>();
    for (ReorderItem item : items) {
      if (item == null || item.id() == null) {
        throw new AppException("VALIDATION_ERROR", "item id is required", 422);
      }
      if (item.priority() == null) {
        throw new AppException("VALIDATION_ERROR", "priority is required", 422);
      }
      ids.add(item.id());
      storeItems.add(new BannerStore.ReorderItem(item.id(), item.priority()));
    }
    List<Banner> found = store.findByIds(ids);
    if (found.size() != ids.size()) {
      throw new AppException("BANNER_NOT_FOUND", "One or more banner IDs not found", 422);
    }
    Set<BannerPlacement> placements = new HashSet<>();
    for (Banner b : found) {
      placements.add(b.placement());
    }
    if (placements.size() > 1) {
      throw new AppException(
          "MIXED_PLACEMENTS", "Items span multiple placements (not allowed)", 422);
    }
    Instant now = clock.instant();
    int updated = store.reorder(storeItems, now);
    auditSafe(
        principal,
        ids.get(0),
        "REORDER",
        Map.of("ids", ids),
        Map.of("updated_count", updated, "reordered_at", now.toString()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated_count", updated);
    data.put("reordered_at", now);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listCustomer(MedmatePrincipal principal, String placement) {
    requireCustomer(principal);
    BannerPlacement pl = parsePlacementRequired(placement);
    Instant now = clock.instant();
    List<Banner> rows = store.listActiveForPlacement(pl, now);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Banner b : rows) {
      items.add(toCustomerItem(b));
    }
    return Map.of("banners", items);
  }

  @Transactional
  public Map<String, Object> logImpression(MedmatePrincipal principal, UUID id, String sessionId) {
    requireCustomer(principal);
    requireBanner(id);
    String sid = sessionId == null || sessionId.isBlank() ? principal.jti() : sessionId.trim();
    if (throttle.tryAcquire(id, principal.subject(), sid)) {
      store.incrementImpressions(id);
    }
    return Map.of("logged", true);
  }

  @Transactional
  public Map<String, Object> logClick(MedmatePrincipal principal, UUID id) {
    requireCustomer(principal);
    requireBanner(id);
    store.incrementClicks(id);
    return Map.of("logged", true);
  }

  @Transactional
  public int deactivateExpired() {
    return store.deactivateExpired(clock.instant());
  }

  private Banner requireBanner(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("BANNER_NOT_FOUND", "Banner ID does not exist", 404));
  }

  private void auditSafe(
      MedmatePrincipal principal,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    try {
      audit.append(
          "banner", principal.subject(), principal.role().name(), entityId, action, before, after);
    } catch (RuntimeException ignored) {
      // must not fail the request
    }
  }

  private static Map<String, Object> toAdminItem(Banner b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", b.id());
    m.put("placement", b.placement().name());
    m.put("headline", b.headline());
    m.put("image_url", b.imageUrl());
    m.put("link_action", Map.of("type", b.linkType().name(), "value", b.linkValue()));
    m.put("impressions", b.impressions());
    m.put("clicks", b.clicks());
    m.put("ctr_pct", b.ctrPct());
    m.put("priority", b.priority());
    m.put("is_live", b.live());
    m.put("valid_from", b.validFrom());
    m.put("valid_until", b.validUntil());
    return m;
  }

  private static Map<String, Object> toCustomerItem(Banner b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", b.id());
    m.put("headline", b.headline());
    m.put("sub_text", b.subText());
    m.put("image_url", b.imageUrl());
    m.put("link_type", b.linkType().name());
    m.put("link_value", b.linkValue());
    m.put("theme_color", b.themeColor());
    m.put("priority", b.priority());
    return m;
  }

  private static Map<String, Object> toAuditMap(Banner b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", b.id().toString());
    m.put("headline", b.headline());
    m.put("placement", b.placement().name());
    m.put("is_live", b.live());
    m.put("priority", b.priority());
    return m;
  }

  private static BannerPlacement parsePlacementRequired(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_PLACEMENT", "Placement not in allowed enum", 422);
    }
    try {
      return BannerPlacement.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_PLACEMENT", "Placement not in allowed enum", 422);
    }
  }

  private static BannerPlacement parsePlacementFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return parsePlacementRequired(raw);
  }

  private static BannerLinkType parseLinkType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "link_type is required", 422);
    }
    try {
      return BannerLinkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "link_type is invalid", 422);
    }
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private static int normalizePage(Integer page) {
    if (page == null || page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return 20;
    }
    return Math.min(limit, 100);
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  }

  private static void requireAdminSuper(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER);
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.CUSTOMER);
  }

  private static void requireRole(MedmatePrincipal principal, AuthRole... allowed) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    for (AuthRole role : allowed) {
      if (principal.role() == role) {
        return;
      }
    }
    throw new AppException("FORBIDDEN", "Insufficient permissions", 403);
  }
}
