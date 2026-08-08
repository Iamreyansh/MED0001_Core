package com.nammamedmate.settings.domain;

import java.util.Set;

public final class AdminStaffRoles {

  public static final String SUPER = "admin_super";
  public static final String OPERATIONS = "admin_operations";
  public static final String FINANCE = "admin_finance";
  public static final String SUPPORT = "admin_support";
  public static final String COMPLIANCE = "admin_compliance";

  public static final Set<String> ALL = Set.of(SUPER, OPERATIONS, FINANCE, SUPPORT, COMPLIANCE);

  /** Roles assignable on invite (admin_super requires elevation via PATCH). */
  public static final Set<String> INVITEABLE = Set.of(OPERATIONS, FINANCE, SUPPORT, COMPLIANCE);

  public static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "INVITED");
  public static final Set<String> PATCH_STATUSES = Set.of("ACTIVE", "SUSPENDED");

  private AdminStaffRoles() {}
}
