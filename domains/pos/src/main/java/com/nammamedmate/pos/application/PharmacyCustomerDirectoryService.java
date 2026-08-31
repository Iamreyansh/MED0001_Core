package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyCustomerDirectoryService {

  private final JdbcTemplate jdbc;

  public PharmacyCustomerDirectoryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public ListResult list(MedmatePrincipal principal, String search, Integer page, Integer limit) {
    UUID pharmacyId = requirePharmacy(principal);
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    String q = search == null || search.isBlank() ? null : "%" + search.trim() + "%";
    String where =
        q == null
            ? "pharmacy_id = ?"
            : "pharmacy_id = ? AND (COALESCE(customer_name,'') ILIKE ? OR COALESCE(customer_phone,'') ILIKE ?)";
    Object[] countArgs = q == null ? new Object[] {pharmacyId} : new Object[] {pharmacyId, q, q};
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT 1 FROM invoice WHERE "
                + where
                + " AND (customer_id IS NOT NULL OR customer_phone IS NOT NULL OR customer_name IS NOT NULL)"
                + " GROUP BY customer_id, customer_name, customer_phone) t",
            Long.class,
            countArgs);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (q != null) {
      args.add(q);
      args.add(q);
    }
    args.add(pr.limit());
    args.add(pr.offset());
    List<Map<String, Object>> items =
        jdbc.query(
            """
            SELECT customer_id, customer_name, customer_phone, MAX(created_at) AS last_purchase_at, COUNT(*) AS invoices
            FROM invoice
            WHERE
            """
                + where
                + """
             AND (customer_id IS NOT NULL OR customer_phone IS NOT NULL OR customer_name IS NOT NULL)
            GROUP BY customer_id, customer_name, customer_phone
            ORDER BY MAX(created_at) DESC
            LIMIT ? OFFSET ?
            """,
            (rs, i) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              UUID cid = (UUID) rs.getObject("customer_id");
              row.put("customer_id", cid == null ? null : cid.toString());
              row.put("name", rs.getString("customer_name"));
              row.put("phone", rs.getString("customer_phone"));
              Timestamp last = rs.getTimestamp("last_purchase_at");
              row.put("last_purchase_at", last == null ? null : last.toInstant().toString());
              row.put("invoices", rs.getLong("invoices"));
              return row;
            },
            args.toArray());
    return new ListResult(
        Map.of("customers", items),
        PaginationMeta.of(pr.page(), pr.limit(), total == null ? 0L : total));
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

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {}
}
