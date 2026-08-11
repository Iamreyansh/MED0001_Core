package com.nammamedmate.medicine_schedule.domain;

import java.util.regex.Pattern;

public record DoseSlot(String slot, String reminderTime) {

  private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

  public DoseSlot {
    DoseSlotName.parse(slot);
    if (reminderTime == null || !HH_MM.matcher(reminderTime.trim()).matches()) {
      throw new IllegalArgumentException("INVALID_REMINDER_TIME");
    }
    slot = DoseSlotName.parse(slot).name();
    reminderTime = reminderTime.trim();
  }
}
