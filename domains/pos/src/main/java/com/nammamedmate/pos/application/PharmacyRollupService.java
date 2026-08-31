package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyRollupService {

  private final JdbcTemplate jdbc;

  public PharmacyRollupService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal) {
    UUID current = requirePharmacy(principal);
    List<Map<String, Object>> assignments =
        jdbc.queryForList(
            """
            SELECT a.pharmacy_id, p.name, p.subscription_plan
            FROM pharmacy_staff_assignment a
            JOIN pharmacies p ON p.id = a.pharmacy_id
            WHERE a.staff_id = ? AND a.is_active = TRUE
            ORDER BY a.joined_at ASC
            """,
            principal.subject());
    List<UUID> ids = new ArrayList<>();
    String currentPlan = "FREE";
    if (assignments.isEmpty()) {
      ids.add(current);
    } else {
      for (Map<String, Object> row : assignments) {
        UUID pharmacyId = (UUID) row.get("pharmacy_id");
        ids.add(pharmacyId);
        if (current.equals(pharmacyId)) {
          currentPlan = String.valueOf(row.get("subscription_plan"));
        }
      }
    }
    if (ids.size() > 1 && !rollupPlan(currentPlan)) {
      throw new AppException(
          "PLAN_UPGRADE_REQUIRED", "Multi-branch rollup requires Growth plan or higher", 403);
    }
    List<Map<String, Object>> branches = new ArrayList<>();
    long invoices = 0L;
    long revenue = 0L;
    for (UUID pharmacyId : ids) {
      List<Map<String, Object>> totals =
          jdbc.queryForList(
              """
              SELECT COUNT(*) AS invoices, COALESCE(SUM(grand_total_paise), 0) AS revenue_paise
              FROM invoice WHERE pharmacy_id = ?
              """,
              pharmacyId);
      Map<String, Object> tot = totals.isEmpty() ? Map.of() : totals.getFirst();
      long inv = tot.get("invoices") == null ? 0L : ((Number) tot.get("invoices")).longValue();
      long rev =
          tot.get("revenue_paise") == null ? 0L : ((Number) tot.get("revenue_paise")).longValue();
      invoices += inv;
      revenue += rev;
      Map<String, Object> branch = new LinkedHashMap<>();
      branch.put("pharmacy_id", pharmacyId.toString());
      String name = null;
      for (Map<String, Object> row : assignments) {
        if (pharmacyId.equals(row.get("pharmacy_id"))) {
          name = row.get("name") == null ? null : String.valueOf(row.get("name"));
          break;
        }
      }
      if (name != null) {
        branch.put("name", name);
      }
      branch.put("invoices", inv);
      branch.put("revenue_paise", rev);
      branches.add(branch);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_count", ids.size());
    data.put("invoices", invoices);
    data.put("revenue_paise", revenue);
    data.put("branches", branches);
    return data;
  }

  static boolean rollupPlan(String plan) {
    return "RETAIL_PRO".equals(plan) || "ENTERPRISE".equals(plan) || "GROWTH".equals(plan);
  }

  private static UUID requirePharmacy(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
    return principal.pharmacyId();
  }
}
