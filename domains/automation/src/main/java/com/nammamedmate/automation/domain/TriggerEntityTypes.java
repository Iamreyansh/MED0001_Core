package com.nammamedmate.automation.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Maps trigger context vars / category → allowed simulation entity_type values. */
public final class TriggerEntityTypes {

  private TriggerEntityTypes() {}

  public static Set<String> allowed(TriggerDefinition trigger) {
    Set<String> out = new LinkedHashSet<>();
    if (trigger != null) {
      for (String v : trigger.availableContextVars()) {
        if (v.isBlank()) {
          continue;
        }
        String root = v;
        int dot = v.indexOf('.');
        if (dot >= 0) {
          root = v.substring(0, dot);
        }
        switch (root.toLowerCase(Locale.ROOT)) {
          case "order" -> out.add("ORDER");
          case "pharmacy", "invoice", "register", "sale", "sku" -> out.add("PHARMACY");
          case "rider" -> out.add("RIDER");
          case "ticket", "csat" -> out.add("TICKET");
          case "customer" -> out.add("CUSTOMER");
          case "payment", "refund" -> {
            out.add("ORDER");
            out.add("CUSTOMER");
          }
          case "payout" -> {
            out.add("PHARMACY");
            out.add("RIDER");
          }
          case "entity" -> {
            out.add("PHARMACY");
            out.add("RIDER");
          }
          case "coupon", "campaign", "segment" -> {
            out.add("CUSTOMER");
            out.add("PHARMACY");
          }
          case "prescription" -> {
            out.add("ORDER");
            out.add("CUSTOMER");
          }
          default -> {
            /* ignore unknown roots */
          }
        }
      }
      if (out.isEmpty()) {
        String cat = "";
        if (trigger.category() != null) {
          cat = trigger.category().toUpperCase(Locale.ROOT);
        }
        switch (cat) {
          case "ORDERS", "DISPATCH" -> out.add("ORDER");
          case "PHARMACY", "CRM", "COMPLIANCE" -> out.add("PHARMACY");
          case "RIDER" -> out.add("RIDER");
          case "SUPPORT" -> out.add("TICKET");
          case "FINANCE" -> {
            out.add("PHARMACY");
            out.add("RIDER");
            out.add("ORDER");
          }
          case "GROWTH" -> {
            out.add("CUSTOMER");
            out.add("PHARMACY");
          }
          default -> out.add("ENTITY");
        }
      }
    }
    return out;
  }

  public static String primaryLabel(Set<String> types) {
    if (types == null || types.isEmpty()) {
      return "entity";
    }
    String first = types.iterator().next();
    return switch (first) {
      case "ORDER" -> "order";
      case "PHARMACY" -> "pharmacy";
      case "RIDER" -> "rider";
      case "TICKET" -> "ticket";
      case "CUSTOMER" -> "customer";
      default -> "entity";
    };
  }
}
