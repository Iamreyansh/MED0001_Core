package com.nammamedmate.marketing.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates custom segment criteria fields/operators (story table). */
public final class CriteriaValidator {

  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "total_orders", Set.of("=", ">", "<", ">=", "<=", "between"),
          "ltv_rs", Set.of(">", "<", ">=", "<=", "between"),
          "last_order_days_ago", Set.of(">", "<", ">=", "<="),
          "avg_order_value_rs", Set.of(">", "<", ">=", "<="),
          "city", Set.of("in", "not_in"),
          "pincode", Set.of("in", "not_in"),
          "has_rx_orders", Set.of("="),
          "loyalty_tier", Set.of("in"));

  private CriteriaValidator() {}

  public static List<SegmentCriterion> validate(List<SegmentCriterion> criteria) {
    if (criteria == null) {
      throw new AppException("EMPTY_CRITERIA", "At least one criterion is required", 422);
    }
    if (criteria.isEmpty()) {
      throw new AppException("EMPTY_CRITERIA", "At least one criterion is required", 422);
    }
    for (SegmentCriterion c : criteria) {
      if (c == null) {
        throw new AppException("INVALID_CRITERIA_FIELD", "Criterion field is required", 422);
      }
      if (c.field() == null || c.field().isBlank()) {
        throw new AppException("INVALID_CRITERIA_FIELD", "Criterion field is required", 422);
      }
      String field = c.field().trim().toLowerCase(Locale.ROOT);
      Set<String> ops = ALLOWED.get(field);
      if (ops == null) {
        throw new AppException(
            "INVALID_CRITERIA_FIELD", "Unsupported criteria field: " + c.field(), 422);
      }
      if (c.operator() == null || c.operator().isBlank()) {
        throw new AppException("INVALID_OPERATOR", "Operator is required", 422);
      }
      String op = c.operator().trim().toLowerCase(Locale.ROOT);
      if (!ops.contains(op)) {
        throw new AppException(
            "INVALID_OPERATOR", "Operator " + c.operator() + " is invalid for field " + field, 422);
      }
      if (c.value() == null) {
        throw new AppException("INVALID_CRITERIA_FIELD", "Criterion value is required", 422);
      }
      boolean needsArray =
          switch (op) {
            case "in", "not_in", "between" -> true;
            default -> false;
          };
      if (needsArray) {
        if (!(c.value() instanceof Collection<?>)) {
          throw new AppException(
              "INVALID_CRITERIA_FIELD",
              "Operator " + op + " requires a non-empty array value",
              422);
        }
        Collection<?> col = (Collection<?>) c.value();
        if (col.isEmpty()) {
          throw new AppException(
              "INVALID_CRITERIA_FIELD",
              "Operator " + op + " requires a non-empty array value",
              422);
        }
        if ("between".equals(op)) {
          if (col.size() != 2) {
            throw new AppException(
                "INVALID_CRITERIA_FIELD", "between requires exactly two values", 422);
          }
        }
      }
    }
    return criteria.stream()
        .map(
            c ->
                new SegmentCriterion(
                    c.field().trim().toLowerCase(Locale.ROOT),
                    c.operator().trim().toLowerCase(Locale.ROOT),
                    c.value()))
        .toList();
  }
}
