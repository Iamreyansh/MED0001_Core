package com.nammamedmate.automation.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Risk + human-readable impact for batch simulation results (BR-5 / BR-6). */
public final class SimulationRiskAssessor {

  /** Irreversible / high-stakes actions per AC-003 + story BR-5. */
  public static final Set<String> IRREVERSIBLE_ACTIONS =
      Set.of("suspend_entity", "release_payout", "mass_payout", "process_refund");

  private SimulationRiskAssessor() {}

  public record Assessment(
      FalsePositiveRisk risk, String riskDetails, String estimatedImpactSummary) {}

  public static Assessment assess(
      int entitiesMatched,
      List<Map<String, Object>> actionsThatWouldFire,
      String entityTypeLabel,
      List<ActionSpec> ruleActions) {
    int matched = Math.max(0, entitiesMatched);
    long irreversibleHits =
        actionsThatWouldFire == null
            ? 0
            : actionsThatWouldFire.stream()
                .map(a -> String.valueOf(a.getOrDefault("action", "")))
                .filter(IRREVERSIBLE_ACTIONS::contains)
                .count();
    Set<String> uniqueEntities = new LinkedHashSet<>();
    if (actionsThatWouldFire != null) {
      for (Map<String, Object> row : actionsThatWouldFire) {
        Object eid = row.get("entity_id");
        if (eid != null) {
          uniqueEntities.add(String.valueOf(eid));
        }
      }
    }
    long irreversibleEntities =
        actionsThatWouldFire == null
            ? 0
            : actionsThatWouldFire.stream()
                .filter(a -> IRREVERSIBLE_ACTIONS.contains(String.valueOf(a.get("action"))))
                .map(a -> String.valueOf(a.get("entity_id")))
                .distinct()
                .count();

    double pct = matched == 0 ? 0.0 : (100.0 * irreversibleEntities) / matched;
    FalsePositiveRisk risk;
    String details;
    if (matched > 0 && pct > 10.0) {
      risk = FalsePositiveRisk.HIGH;
      details =
          String.format(
              Locale.ROOT,
              "%.1f%% of matched entities would receive irreversible actions (payouts/suspensions).",
              pct);
    } else if (matched > 0 && pct > 5.0) {
      risk = FalsePositiveRisk.MEDIUM;
      details =
          String.format(
              Locale.ROOT, "%.1f%% of matched entities would receive irreversible actions.", pct);
    } else if (irreversibleHits == 0) {
      risk = FalsePositiveRisk.LOW;
      details = "All actions are reversible notifications or low-impact; no financial impact.";
    } else {
      risk = FalsePositiveRisk.LOW;
      details =
          String.format(
              Locale.ROOT,
              "Irreversible actions affect %.1f%% of matched entities (at or below 5%%).",
              pct);
    }

    String rawLabel = "entity";
    if (entityTypeLabel != null && !entityTypeLabel.isBlank()) {
      rawLabel = entityTypeLabel;
    }
    String label = pluralize(rawLabel, matched);
    String actionPhrase = summarizeActions(ruleActions, matched, label);
    int fireCount = uniqueEntities.isEmpty() ? matched : uniqueEntities.size();
    String summary =
        String.format(
            Locale.ROOT,
            "Would have fired %d times in the last 7 days, affecting %d %s, %s.",
            fireCount,
            matched,
            label,
            actionPhrase);
    return new Assessment(risk, details, summary);
  }

  private static String summarizeActions(List<ActionSpec> actions, int matched, String label) {
    if (actions == null || actions.isEmpty()) {
      return "executing no actions";
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ActionSpec a : actions) {
      counts.merge(a.actionId(), matched, Integer::sum);
    }
    StringBuilder sb = new StringBuilder("executing ");
    boolean first = true;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(e.getKey()).append(" for ").append(e.getValue()).append(' ').append(label);
    }
    return sb.toString();
  }

  private static String pluralize(String singular, int n) {
    String s = singular.toLowerCase(Locale.ROOT);
    if (n == 1) {
      return s;
    }
    if (s.endsWith("ies") || s.endsWith("s")) {
      return s;
    }
    if (s.endsWith("y") && s.length() > 1) {
      char before = s.charAt(s.length() - 2);
      if (!isVowel(before)) {
        return s.substring(0, s.length() - 1) + "ies";
      }
    }
    return s + "s";
  }

  private static boolean isVowel(char c) {
    return "aeiou".indexOf(Character.toLowerCase(c)) >= 0;
  }
}
