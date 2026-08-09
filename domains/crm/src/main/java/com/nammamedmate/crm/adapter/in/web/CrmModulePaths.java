package com.nammamedmate.crm.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pharmacy ERP path prefix → saas_module_matrix.module_id. Longer prefixes first. */
public final class CrmModulePaths {

  private static final Map<String, String> PATH_MODULES = pathModules();

  private CrmModulePaths() {}

  public static String resolveModule(String uri) {
    if (uri == null) {
      return null;
    }
    for (Map.Entry<String, String> e : PATH_MODULES.entrySet()) {
      if (uri.contains(e.getKey())) {
        return e.getValue();
      }
    }
    return null;
  }

  public static Map<String, String> pathModules() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("/api/v1/pharmacy/rack-locations", "mod_inventory");
    m.put("/api/v1/pharmacy/inventory", "mod_inventory");
    m.put("/api/v1/pharmacy/invoices", "mod_billing");
    m.put("/api/v1/pharmacy/invoice-settings", "mod_billing");
    m.put("/api/v1/pharmacy/pos", "mod_billing");
    m.put("/api/v1/pharmacy/sales", "mod_billing");
    m.put("/api/v1/pharmacy/purchases", "mod_purchase_orders");
    m.put("/api/v1/pharmacy/khata", "mod_khata");
    m.put("/api/v1/pharmacy/offers", "mod_offers");
    m.put("/api/v1/pharmacy/distributors", "mod_distributors");
    m.put("/api/v1/pharmacy/reorder", "mod_reorder");
    m.put("/api/v1/pharmacy/roles", "mod_staff");
    return Map.copyOf(m);
  }
}
