package com.nammamedmate.auth.domain;

import java.util.List;
import java.util.Map;

/**
 * Fixed admin role matrix (immutable). admin_super wildcard is also enforced in RBAC middleware.
 */
public final class AdminRoleDefinitions {

  public record AdminRole(
      String role,
      String displayName,
      String description,
      boolean system,
      List<String> permissions) {
    public AdminRole {
      permissions = List.copyOf(permissions);
    }
  }

  public static final List<AdminRole> ALL =
      List.of(
          new AdminRole(
              "admin_super",
              "Super Administrator",
              "Full platform access with no restrictions. Required for MFA.",
              true,
              List.of("*:*")),
          new AdminRole(
              "admin_operations",
              "Operations Manager",
              "Manages orders, logistics, pharmacies, and riders.",
              true,
              List.of(
                  "orders:*",
                  "pharmacies:read",
                  "pharmacies:update",
                  "riders:*",
                  "logistics:*",
                  "catalogue:read",
                  "customers:read")),
          new AdminRole(
              "admin_finance",
              "Finance Manager",
              "Manages settlements, refunds, payouts, and financial analytics.",
              true,
              List.of(
                  "finance:*",
                  "settlements:*",
                  "refunds:*",
                  "taxes:*",
                  "analytics:finance",
                  "customers:read")),
          new AdminRole(
              "admin_support",
              "Customer Support",
              "Handles tickets, disputes, and customer-facing issues.",
              true,
              List.of(
                  "tickets:*", "disputes:*", "customers:read", "customers:notify", "orders:read")),
          new AdminRole(
              "admin_compliance",
              "Compliance Officer",
              "Oversees prescription validation, catalogue compliance, and pharmacy KYC.",
              true,
              List.of(
                  "prescriptions:*",
                  "compliance:*",
                  "catalogue:update",
                  "pharmacies:read",
                  "pharmacies:update",
                  "customers:read")));

  private static final Map<String, AdminRole> BY_ROLE =
      ALL.stream().collect(java.util.stream.Collectors.toMap(AdminRole::role, r -> r));

  private AdminRoleDefinitions() {}

  public static AdminRole require(String role) {
    AdminRole found = BY_ROLE.get(role);
    if (found == null) {
      throw new IllegalArgumentException("Unknown admin role: " + role);
    }
    return found;
  }

  public static List<String> permissionsFor(String role) {
    return require(role).permissions();
  }
}
