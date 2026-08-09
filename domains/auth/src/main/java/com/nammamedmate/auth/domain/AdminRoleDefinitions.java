package com.nammamedmate.auth.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fixed admin role matrix (immutable). API-facing lists match EPIC-021 STORY-005; enforcement
 * unions live {@code @RequiresPermission} extras so shipped endpoints do not regress.
 */
public final class AdminRoleDefinitions {

  public static final String SUPER_NOTES =
      "Implicit wildcard - grants all current and future permissions.";

  public record AdminRole(
      String role,
      String displayName,
      String description,
      boolean system,
      boolean customizable,
      List<String> permissions,
      Integer permissionCount,
      String notes) {
    public AdminRole {
      permissions = List.copyOf(permissions);
    }
  }

  public static final List<AdminRole> ALL =
      List.of(
          new AdminRole(
              "admin_super",
              "Super Administrator",
              "Full platform access. MFA required on every login.",
              true,
              false,
              List.of("*:*"),
              null,
              SUPER_NOTES),
          new AdminRole(
              "admin_operations",
              "Operations Manager",
              "Manages day-to-day order fulfilment, pharmacy operations, and logistics.",
              true,
              false,
              List.of(
                  "orders:read",
                  "orders:write",
                  "orders:cancel",
                  "orders:assign-rider",
                  "pharmacies:read",
                  "pharmacies:update",
                  "riders:read",
                  "riders:write",
                  "riders:assign",
                  "riders:suspend",
                  "logistics:read",
                  "logistics:update",
                  "catalogue:read"),
              13,
              null),
          new AdminRole(
              "admin_finance",
              "Finance Manager",
              "Manages settlements, refunds, payouts, and financial analytics.",
              true,
              false,
              List.of(
                  "finance:read",
                  "finance:write",
                  "finance:release-payout",
                  "settlements:read",
                  "settlements:process",
                  "refunds:read",
                  "refunds:approve",
                  "refunds:reject",
                  "taxes:read",
                  "taxes:export",
                  "analytics:finance",
                  "customers:read",
                  "wallet:credit"),
              13,
              null),
          new AdminRole(
              "admin_support",
              "Customer Support Agent",
              "Handles tickets, disputes, and customer communication.",
              true,
              false,
              List.of(
                  "tickets:read",
                  "tickets:write",
                  "tickets:close",
                  "disputes:read",
                  "disputes:write",
                  "disputes:resolve",
                  "customers:read",
                  "customers:notify",
                  "customers:flag",
                  "orders:read"),
              10,
              null),
          new AdminRole(
              "admin_compliance",
              "Compliance Officer",
              "Oversees prescription validation, catalogue compliance, and pharmacy KYC.",
              true,
              false,
              List.of(
                  "prescriptions:read",
                  "prescriptions:review",
                  "prescriptions:approve",
                  "prescriptions:reject",
                  "compliance:read",
                  "compliance:audit",
                  "compliance:flag",
                  "catalogue:read",
                  "catalogue:update",
                  "pharmacies:read",
                  "kyc:read",
                  "kyc:approve",
                  "kyc:reject"),
              13,
              null));

  /**
   * Live {@code @RequiresPermission} values (and retained wildcards) unioned for enforcement only —
   * not returned by GET /admin/roles.
   */
  private static final Map<String, List<String>> LIVE_EXTRA =
      Map.of(
          "admin_operations",
          List.of(
              "pharmacies:suspend",
              "orders:dispatch",
              "customers:read",
              "finance:read",
              "crm:read",
              "crm:write",
              "orders:*",
              "riders:*",
              "logistics:*"),
          "admin_finance",
          List.of(
              "finance:update",
              "finance:*",
              "pharmacies:read",
              "crm:read",
              "crm:update",
              "crm:analytics"),
          "admin_support",
          List.of("pharmacies:read", "finance:read"),
          "admin_compliance",
          List.of("pharmacies:update", "customers:read", "taxes:read", "taxes:export"));

  private static final Map<String, String> DESCRIPTIONS = buildDescriptions();

  private static final Map<String, AdminRole> BY_ROLE =
      ALL.stream().collect(java.util.stream.Collectors.toMap(AdminRole::role, r -> r));

  private static final Map<String, List<String>> ENFORCEMENT_BY_ROLE = buildEnforcement();

  private AdminRoleDefinitions() {}

  public static Optional<AdminRole> find(String role) {
    return Optional.ofNullable(BY_ROLE.get(role));
  }

  public static AdminRole require(String role) {
    AdminRole found = BY_ROLE.get(role);
    if (found == null) {
      throw new IllegalArgumentException("Unknown admin role: " + role);
    }
    return found;
  }

  /** API-facing story permission list (GET /admin/roles). */
  public static List<String> permissionsFor(String role) {
    return require(role).permissions();
  }

  /** Story list ∪ live extras used by {@code RbacPermissionService.hasPermission}. */
  public static List<String> enforcementPermissionsFor(String role) {
    List<String> found = ENFORCEMENT_BY_ROLE.get(role);
    if (found == null) {
      throw new IllegalArgumentException("Unknown admin role: " + role);
    }
    return found;
  }

  public static String descriptionFor(String permission) {
    return DESCRIPTIONS.getOrDefault(permission, "Admin permission: " + permission);
  }

  public static Map<String, Object> toPermissionObject(String permission) {
    int sep = permission.indexOf(':');
    String resource;
    String action;
    if (sep > 0) {
      resource = permission.substring(0, sep);
      action = permission.substring(sep + 1);
    } else {
      resource = permission;
      action = "";
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("permission", permission);
    map.put("resource", resource);
    map.put("action", action);
    map.put("description", descriptionFor(permission));
    return map;
  }

  private static Map<String, List<String>> buildEnforcement() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (AdminRole role : ALL) {
      Set<String> union = new LinkedHashSet<>(role.permissions());
      List<String> extras = LIVE_EXTRA.getOrDefault(role.role(), List.of());
      union.addAll(extras);
      out.put(role.role(), List.copyOf(union));
    }
    return Map.copyOf(out);
  }

  /** Exposed for documentation / tests — live extras not in the story API matrix. */
  public static Map<String, List<String>> liveExtras() {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    LIVE_EXTRA.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    return Map.copyOf(copy);
  }

  private static Map<String, String> buildDescriptions() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("*:*", "Implicit wildcard — grants all current and future permissions");
    m.put("orders:read", "View order details and history");
    m.put("orders:write", "Create and modify orders");
    m.put("orders:cancel", "Cancel any order");
    m.put("orders:assign-rider", "Manually assign or reassign riders to orders");
    m.put("pharmacies:read", "View pharmacy profiles and details");
    m.put("pharmacies:update", "Update pharmacy information");
    m.put("riders:read", "View rider profiles and status");
    m.put("riders:write", "Create and modify rider records");
    m.put("riders:assign", "Manually assign riders to orders");
    m.put("riders:suspend", "Suspend or block riders");
    m.put("logistics:read", "View delivery and logistics data");
    m.put("logistics:update", "Update logistics configuration");
    m.put("catalogue:read", "Browse the medicine catalogue");
    m.put("catalogue:update", "Update medicine information and categories");
    m.put("finance:read", "View financial reports, summaries, and P&L data");
    m.put("finance:write", "Create and modify financial adjustments");
    m.put("finance:release-payout", "Trigger pharmacy payout releases to bank accounts");
    m.put("settlements:read", "View settlement records and breakdowns");
    m.put("settlements:process", "Process pending pharmacy settlements");
    m.put("refunds:read", "View refund requests and history");
    m.put("refunds:approve", "Approve pending customer refund requests");
    m.put("refunds:reject", "Reject refund requests with reason");
    m.put("taxes:read", "View GST and tax reports");
    m.put("taxes:export", "Export tax reports as CSV/PDF for filing");
    m.put("analytics:finance", "Access finance-specific analytics dashboards and revenue reports");
    m.put("customers:read", "View customer profiles for financial investigation");
    m.put("wallet:credit", "Manually credit customer wallets for refunds or goodwill");
    m.put("tickets:read", "View support tickets");
    m.put("tickets:write", "Create and update support tickets");
    m.put("tickets:close", "Close support tickets");
    m.put("disputes:read", "View customer disputes");
    m.put("disputes:write", "Create and update disputes");
    m.put("disputes:resolve", "Resolve open disputes");
    m.put("customers:notify", "Send notifications to customers");
    m.put("customers:flag", "Flag customer accounts for review");
    m.put("prescriptions:read", "View prescription records");
    m.put("prescriptions:review", "Review prescriptions pending validation");
    m.put("prescriptions:approve", "Approve prescriptions");
    m.put("prescriptions:reject", "Reject prescriptions");
    m.put("compliance:read", "View compliance dashboards");
    m.put("compliance:audit", "Run compliance audits on pharmacies");
    m.put("compliance:flag", "Flag compliance issues");
    m.put("kyc:read", "View pharmacy KYC documents");
    m.put("kyc:approve", "Approve pharmacy KYC");
    m.put("kyc:reject", "Reject pharmacy KYC");
    m.put(
        "crm:read",
        "View SaaS plans, add-ons, invoices, leads, module matrix, adoption, account health, renewals, and churn analysis");
    m.put(
        "crm:write",
        "Manage leads, add-ons, modules, adoption nudges, CSM save plays, manual renewals, and churn surveys");
    m.put("crm:update", "Update SaaS plan pricing/limits and mark invoices paid");
    m.put(
        "crm:analytics",
        "Access SaaS revenue analytics (MRR/ARR/NRR/GRR, cohorts, unit economics, reports)");
    return Map.copyOf(m);
  }

  /** All distinct API-facing permission strings across roles (for catalog seeding helpers). */
  public static List<String> allApiPermissions() {
    Set<String> all = new LinkedHashSet<>();
    for (AdminRole role : ALL) {
      all.addAll(role.permissions());
    }
    return new ArrayList<>(all);
  }
}
