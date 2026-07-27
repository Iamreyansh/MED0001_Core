package com.nammamedmate.pharmacy.domain;

import com.nammamedmate.kernel.error.AppException;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OperatingHoursValidator {

  private static final String[] DAY_NAMES = {
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
  };

  private OperatingHoursValidator() {}

  public static String dayName(int dayOfWeek) {
    if (dayOfWeek < 0 || dayOfWeek > 6) {
      return "Unknown";
    }
    return DAY_NAMES[dayOfWeek];
  }

  public static void requireValid(List<Map<String, Object>> rawHours) {
    if (rawHours == null || rawHours.size() != 7) {
      throw new AppException(
          "INVALID_OPERATING_HOURS", "operating_hours must contain exactly 7 days", 400);
    }
    Set<Integer> seen = new HashSet<>();
    for (Map<String, Object> entry : rawHours) {
      if (entry == null) {
        throw new AppException("INVALID_OPERATING_HOURS", "Invalid operating hours entry", 400);
      }
      Object dow = entry.get("day_of_week");
      if (!(dow instanceof Number)) {
        throw new AppException("INVALID_OPERATING_HOURS", "day_of_week is required", 400);
      }
      int day = ((Number) dow).intValue();
      if (day < 0 || day > 6) {
        throw new AppException("INVALID_OPERATING_HOURS", "day_of_week must be 0-6", 400);
      }
      if (!seen.add(day)) {
        throw new AppException("INVALID_OPERATING_HOURS", "Duplicate day_of_week", 400);
      }
      boolean closed = Boolean.TRUE.equals(entry.get("is_closed"));
      if (closed) {
        continue;
      }
      LocalTime open = parseTime(entry.get("open_time"), "open_time");
      LocalTime close = parseTime(entry.get("close_time"), "close_time");
      if (!open.isBefore(close)) {
        throw new AppException(
            "INVALID_OPERATING_HOURS", "open_time must be before close_time", 400);
      }
    }
  }

  private static LocalTime parseTime(Object raw, String field) {
    if (raw == null) {
      throw new AppException("INVALID_OPERATING_HOURS", field + " is required when open", 400);
    }
    try {
      return LocalTime.parse(String.valueOf(raw).trim());
    } catch (Exception ex) {
      throw new AppException("INVALID_OPERATING_HOURS", "Invalid " + field, 400);
    }
  }
}
