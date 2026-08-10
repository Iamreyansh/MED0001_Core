package com.nammamedmate.marketing.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.application.port.out.SegmentUsagePort;
import com.nammamedmate.marketing.domain.CriteriaValidator;
import com.nammamedmate.marketing.domain.MoneyFormats;
import com.nammamedmate.marketing.domain.RecommendedActions;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SegmentService {

  private final SegmentStore store;
  private final SegmentUsagePort usagePort;
  private final Clock clock;

  public SegmentService(SegmentStore store, SegmentUsagePort usagePort, Clock clock) {
    this.store = store;
    this.usagePort = usagePort;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {}

  @Transactional(readOnly = true)
  public PagedResult list(
      MedmatePrincipal principal, String segmentType, Integer page, Integer limit) {
    requireAdminRead(principal);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    SegmentType type = parseTypeFilter(segmentType);
    long total = store.count(type);
    List<Segment> rows = store.list(type, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Segment s : rows) {
      items.add(toListItem(s));
    }
    return new PagedResult(Map.of("segments", items), PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String name,
      String description,
      List<SegmentCriterion> criteria) {
    requireAdminWrite(principal);
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 422);
    }
    String trimmed = name.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name must be at most 100 characters", 422);
    }
    List<SegmentCriterion> validated = CriteriaValidator.validate(criteria);
    if (store.findByNameIgnoreCase(trimmed).isPresent()) {
      throw new AppException("SEGMENT_NAME_EXISTS", "Segment name already in use", 409);
    }
    Instant now = clock.instant();
    Segment created =
        store.insert(
            SegmentStore.newCustom(
                Ids.newId(), trimmed, description, validated, principal.subject(), now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", created.id());
    data.put("name", created.name());
    data.put("segment_type", created.segmentType().name());
    data.put("status", created.status());
    data.put("created_at", created.createdAt());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdminRead(principal);
    Segment s = requireSegment(id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", s.id());
    data.put("name", s.name());
    data.put("segment_type", s.segmentType().name());
    data.put("customer_count", s.customerCount());
    data.put("criteria", toCriteriaMaps(s.criteria()));
    data.put("avg_aov_rs", paiseOrNull(s.avgAovPaise()));
    data.put("avg_ltv_rs", avgLtvRs(s));
    data.put("growth_chart", toGrowthChart(store.growthChart(id, 12)));
    data.put("recommended_actions", RecommendedActions.forSegment(s.name(), s.segmentType()));
    data.put("last_computed_at", s.lastComputedAt());
    data.put("description", s.description());
    data.put("status", s.status());
    return data;
  }

  @Transactional
  public Map<String, Object> enqueueCompute(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    requireSegment(id);
    UUID jobId = store.enqueueComputeJob(id, clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("job_id", jobId);
    data.put("status", "ENQUEUED");
    data.put("message", "Segment computation enqueued. Results available in 2-5 minutes.");
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id) {
    requireAdminSuper(principal);
    Segment s = requireSegment(id);
    if (s.isSystem()) {
      throw new AppException(
          "CANNOT_DELETE_SYSTEM_SEGMENT", "System segments cannot be deleted", 403);
    }
    if (usagePort.isReferencedByActiveCouponOrCampaign(id)) {
      throw new AppException(
          "SEGMENT_IN_USE", "Segment is referenced by an active campaign or coupon", 409);
    }
    store.softDelete(id, clock.instant());
    return Map.of("id", id, "deleted", true);
  }

  @Transactional(readOnly = true)
  public PagedResult listCustomers(
      MedmatePrincipal principal, UUID id, Integer page, Integer limit, String sort, String order) {
    requireAdminOps(principal);
    requireSegment(id);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    String sortKey = (sort == null || sort.isBlank()) ? "ltv_rs" : sort.trim();
    String ord = (order == null || order.isBlank()) ? "desc" : order.trim();
    SegmentStore.PagedMemberships pageRows =
        store.listMembers(id, sortKey, ord, (p - 1) * lim, lim);
    List<Map<String, Object>> customers = new ArrayList<>(pageRows.customers().size());
    for (SegmentStore.MembershipCustomer c : pageRows.customers()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", c.customerId());
      row.put("name", c.name());
      row.put("phone", c.phone());
      row.put("total_orders", c.totalOrders());
      row.put("ltv_rs", MoneyFormats.paiseToRupees(c.ltvPaise()));
      row.put("last_order_at", c.lastOrderAt());
      customers.add(row);
    }
    return new PagedResult(
        Map.of("customers", customers), PaginationMeta.of(p, lim, pageRows.total()));
  }

  private Segment requireSegment(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("SEGMENT_NOT_FOUND", "Segment not found", 404));
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

  private static SegmentType parseTypeFilter(String segmentType) {
    if (segmentType == null || segmentType.isBlank()) {
      return null;
    }
    try {
      return SegmentType.valueOf(segmentType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "segment_type must be SYSTEM or CUSTOM", 422);
    }
  }

  private static Map<String, Object> toListItem(Segment s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", s.id());
    m.put("name", s.name());
    m.put("description", s.description());
    m.put("customer_count", s.customerCount());
    m.put("avg_aov_rs", paiseOrNull(s.avgAovPaise()));
    m.put("total_ltv_rs", paiseOrNull(s.totalLtvPaise()));
    m.put("last_computed_at", s.lastComputedAt());
    m.put("segment_type", s.segmentType().name());
    return m;
  }

  private static BigDecimal paiseOrNull(Long paise) {
    return paise == null ? null : MoneyFormats.paiseToRupees(paise);
  }

  private static BigDecimal avgLtvRs(Segment s) {
    if (s.customerCount() <= 0 || s.totalLtvPaise() == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    long avgPaise = s.totalLtvPaise() / s.customerCount();
    return MoneyFormats.paiseToRupees(avgPaise);
  }

  private static List<Map<String, Object>> toCriteriaMaps(List<SegmentCriterion> criteria) {
    if (criteria == null) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>(criteria.size());
    for (SegmentCriterion c : criteria) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("field", c.field());
      m.put("operator", c.operator());
      m.put("value", c.value());
      out.add(m);
    }
    return out;
  }

  private static List<Map<String, Object>> toGrowthChart(List<SegmentStore.SnapshotPoint> points) {
    return points.stream()
        .sorted(Comparator.comparing(SegmentStore.SnapshotPoint::date))
        .map(
            p -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("date", p.date().toString());
              m.put("count", p.count());
              return m;
            })
        .toList();
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  }

  private static void requireAdminOps(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  }

  private static void requireAdminSuper(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER);
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
    throw new AppException("FORBIDDEN", "Insufficient role", 403);
  }
}
