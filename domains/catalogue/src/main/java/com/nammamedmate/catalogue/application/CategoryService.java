package com.nammamedmate.catalogue.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.ActiveMedicineCountPort;
import com.nammamedmate.catalogue.application.port.out.CategoryListCachePort;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.CategoryRow;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.IOException;
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
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

  private static final int WINDOW = 60;
  private static final int PUBLIC_LIMIT = 120;
  private static final int CREATE_LIMIT = 20;
  private static final int UPDATE_LIMIT = 20;
  private static final int DELETE_LIMIT = 10;
  private static final int REORDER_LIMIT = 10;
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final CategoryStore store;
  private final ActiveMedicineCountPort medicineCount;
  private final CategoryListCachePort cache;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public CategoryService(
      CategoryStore store,
      ActiveMedicineCountPort medicineCount,
      CategoryListCachePort cache,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper) {
    this.store = store;
    this.medicineCount = medicineCount;
    this.cache = cache;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public record CategoryListResult(Map<String, Object> data, Map<String, Object> meta) {
    public CategoryListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
      meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
  }

  @Transactional(readOnly = true)
  public CategoryListResult listPublic(
      MedmatePrincipal principal, boolean includeHidden, boolean includeDeleted, String clientIp) {
    rateLimit("catalogue:categories:public:" + normalizeIp(clientIp), PUBLIC_LIMIT);

    boolean admin = isAdminReader(principal);
    boolean adminHidden = includeHidden && admin;
    boolean adminDeleted = includeDeleted && admin;
    boolean skipCache = adminHidden || adminDeleted;
    if (!skipCache) {
      Map<String, Object> cached = readCache();
      if (cached != null) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
            (List<Map<String, Object>>) cached.getOrDefault("categories", List.of());
        Object total = cached.getOrDefault("total", categories.size());
        Object cachedAt = cached.get("cached_at");
        Map<String, Object> data = Map.of("categories", categories);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", total);
        meta.put("cached_at", cachedAt);
        return new CategoryListResult(data, meta);
      }
    }

    List<CategoryRow> rows = store.list(adminHidden, adminDeleted);
    Instant now = clock.instant();
    List<Map<String, Object>> categories = new ArrayList<>();
    for (CategoryRow row : rows) {
      categories.add(toPublicMap(row, adminDeleted || adminHidden));
    }
    Map<String, Object> data = Map.of("categories", categories);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("total", categories.size());
    meta.put("cached_at", now.toString());

    if (!skipCache) {
      writeCache(categories, categories.size(), now);
    }
    return new CategoryListResult(data, meta);
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String name,
      String slug,
      String iconUrl,
      Boolean visible,
      Integer displayOrder) {
    requireMutateRole(principal);
    rateLimit("admin:catalogue:categories:create:" + principal.subject(), CREATE_LIMIT);

    String trimmedName = requireName(name);
    String trimmedSlug = requireSlug(slug);
    String trimmedIcon = requireIconUrl(iconUrl);
    boolean isVisible = visible == null || visible;
    int order =
        displayOrder == null ? store.nextDisplayOrder() : requirePositiveOrder(displayOrder);

    if (store.existsBySlug(trimmedSlug)) {
      throw new AppException("DUPLICATE_SLUG", "Category slug already exists", 409);
    }
    if (store.existsByName(trimmedName)) {
      throw new AppException("DUPLICATE_NAME", "Category name already exists", 409);
    }

    Instant now = clock.instant();
    UUID id = Ids.newId();
    CategoryRow row =
        new CategoryRow(
            id, trimmedName, trimmedSlug, trimmedIcon, isVisible, order, null, now, now, 0);
    try {
      store.insert(row);
    } catch (DuplicateKeyException ex) {
      throw mapDuplicate(ex);
    }
    cache.invalidate();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("category_id", id.toString());
    data.put("name", trimmedName);
    data.put("slug", trimmedSlug);
    data.put("icon_url", trimmedIcon);
    data.put("is_visible", isVisible);
    data.put("display_order", order);
    data.put("medicine_count", 0);
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal,
      UUID id,
      String name,
      String iconUrl,
      Boolean visible,
      Integer displayOrder) {
    requireMutateRole(principal);
    rateLimit("admin:catalogue:categories:update:" + principal.subject(), UPDATE_LIMIT);

    CategoryRow existing =
        store
            .findById(id)
            .filter(r -> r.deletedAt() == null)
            .orElseThrow(() -> new AppException("CATEGORY_NOT_FOUND", "Category not found", 404));

    List<String> updated = new ArrayList<>();
    String newName = existing.name();
    if (name != null) {
      newName = requireName(name);
      if (!newName.equals(existing.name())) {
        if (store.existsByNameExcluding(newName, id)) {
          throw new AppException("DUPLICATE_NAME", "Category name already exists", 409);
        }
        updated.add("name");
      }
    }
    String newIcon = existing.iconUrl();
    if (iconUrl != null) {
      newIcon = requireIconUrl(iconUrl);
      if (!newIcon.equals(existing.iconUrl())) {
        updated.add("icon_url");
      }
    }
    Boolean newVisible = null;
    if (visible != null) {
      newVisible = visible;
      if (visible != existing.visible()) {
        updated.add("is_visible");
      }
    }
    Integer newOrder = null;
    if (displayOrder != null) {
      newOrder = requirePositiveOrder(displayOrder);
      if (newOrder != existing.displayOrder()) {
        updated.add("display_order");
      }
    }

    Instant now = clock.instant();
    if (!updated.isEmpty()) {
      try {
        store.update(
            id,
            updated.contains("name") ? newName : null,
            updated.contains("icon_url") ? newIcon : null,
            updated.contains("is_visible") ? newVisible : null,
            updated.contains("display_order") ? newOrder : null,
            now);
      } catch (DuplicateKeyException ex) {
        throw mapDuplicate(ex);
      }
      cache.invalidate();
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("category_id", id.toString());
    data.put("updated_fields", List.copyOf(updated));
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id) {
    requireSuperRole(principal);
    rateLimit("admin:catalogue:categories:delete:" + principal.subject(), DELETE_LIMIT);

    store
        .findById(id)
        .filter(r -> r.deletedAt() == null)
        .orElseThrow(() -> new AppException("CATEGORY_NOT_FOUND", "Category not found", 404));

    int active = medicineCount.countActiveByCategoryId(id);
    if (active > 0) {
      throw new AppException(
          "CATEGORY_HAS_ACTIVE_MEDICINES",
          "Category has active medicines; move or ban them first",
          409);
    }

    Instant now = clock.instant();
    store.softDelete(id, now);
    cache.invalidate();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("category_id", id.toString());
    data.put("deleted", true);
    data.put("deleted_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> reorder(MedmatePrincipal principal, List<ReorderItem> items) {
    requireMutateRole(principal);
    rateLimit("admin:catalogue:categories:reorder:" + principal.subject(), REORDER_LIMIT);

    if (items == null || items.isEmpty()) {
      throw new AppException("ITEMS_REQUIRED", "items array is required", 400);
    }

    Set<Integer> orders = new HashSet<>();
    List<UUID> ids = new ArrayList<>();
    for (ReorderItem item : items) {
      if (item == null || item.id() == null) {
        throw new AppException("INVALID_CATEGORY_ID", "One or more category IDs are invalid", 400);
      }
      requirePositiveOrder(item.displayOrder());
      if (!orders.add(item.displayOrder())) {
        throw new AppException(
            "DUPLICATE_DISPLAY_ORDER", "Duplicate display_order in reorder request", 400);
      }
      ids.add(item.id());
    }

    int found = store.countExistingIds(ids);
    if (found != ids.size()) {
      throw new AppException("INVALID_CATEGORY_ID", "One or more category IDs are invalid", 400);
    }

    Instant now = clock.instant();
    store.reorder(items, now);
    cache.invalidate();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reordered_count", items.size());
    data.put("updated_at", now.toString());
    return data;
  }

  private Map<String, Object> readCache() {
    try {
      return cache
          .get()
          .filter(raw -> !raw.isBlank())
          .map(
              raw -> {
                try {
                  return objectMapper.readValue(raw, MAP_TYPE);
                } catch (IOException ex) {
                  return null;
                }
              })
          .orElse(null);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private void writeCache(List<Map<String, Object>> categories, int total, Instant cachedAt) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("categories", categories);
      payload.put("total", total);
      payload.put("cached_at", cachedAt.toString());
      cache.put(objectMapper.writeValueAsString(payload));
    } catch (IOException | RuntimeException ignored) {
      // cache is best-effort
    }
  }

  private static Map<String, Object> toPublicMap(CategoryRow row, boolean includeAdminFlags) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("category_id", row.id().toString());
    m.put("name", row.name());
    m.put("slug", row.slug());
    m.put("icon_url", row.iconUrl());
    m.put("is_visible", row.visible());
    m.put("display_order", row.displayOrder());
    m.put("medicine_count", row.medicineCount());
    if (includeAdminFlags) {
      m.put("is_deleted", row.deletedAt() != null);
      if (row.deletedAt() != null) {
        m.put("deleted_at", row.deletedAt().toString());
      }
    }
    return m;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    String trimmed = name.trim();
    if (trimmed.length() < 2 || trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name must be 2-100 characters", 400);
    }
    return trimmed;
  }

  private static String requireSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new AppException("INVALID_SLUG_FORMAT", "slug is required", 400);
    }
    String trimmed = slug.trim();
    if (trimmed.length() > 100 || !SLUG.matcher(trimmed).matches()) {
      throw new AppException("INVALID_SLUG_FORMAT", "slug must match ^[a-z0-9-]+$ (max 100)", 400);
    }
    return trimmed;
  }

  private static String requireIconUrl(String iconUrl) {
    if (iconUrl == null || iconUrl.isBlank()) {
      throw new AppException("INVALID_ICON_URL", "icon_url is required", 400);
    }
    String trimmed = iconUrl.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("https://") || !(lower.endsWith(".svg") || lower.endsWith(".png"))) {
      throw new AppException(
          "INVALID_ICON_URL", "icon_url must be a valid HTTPS CDN URL ending in .svg or .png", 400);
    }
    return trimmed;
  }

  private static int requirePositiveOrder(int displayOrder) {
    if (displayOrder < 1) {
      throw new AppException("VALIDATION_ERROR", "display_order must be a positive integer", 400);
    }
    return displayOrder;
  }

  private static AppException mapDuplicate(DuplicateKeyException ex) {
    String msg = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
    if (msg.contains("slug")) {
      return new AppException("DUPLICATE_SLUG", "Category slug already exists", 409);
    }
    if (msg.contains("name")) {
      return new AppException("DUPLICATE_NAME", "Category name already exists", 409);
    }
    return new AppException("DUPLICATE_SLUG", "Category already exists", 409);
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static String normalizeIp(String clientIp) {
    return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
  }

  private static boolean isAdminReader(MedmatePrincipal principal) {
    if (principal == null) {
      return false;
    }
    AuthRole role = principal.role();
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private static void requireMutateRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_operations may manage categories", 403);
    }
  }

  private static void requireSuperRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may delete categories", 403);
    }
  }
}
