package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.CustomerNamePort;
import com.nammamedmate.medicine_schedule.application.port.out.MemberCascadePort;
import com.nammamedmate.medicine_schedule.application.port.out.MemberStatsPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort;
import com.nammamedmate.medicine_schedule.application.port.out.TodayAdherencePort;
import com.nammamedmate.medicine_schedule.domain.CareCircleRelationship;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CareCircleService {

  public static final int MAX_MEMBERS = 10;
  public static final String DEFAULT_AVATAR_EMOJI = "👤";
  public static final String DEFAULT_AVATAR_COLOR = "#6B7280";

  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  private final CareCircleMemberStore store;
  private final CustomerNamePort customerNames;
  private final MemberCascadePort cascade;
  private final MemberStatsPort memberStats;
  private final TodayAdherencePort todayAdherence;
  private final RefillAlertQueryPort refillAlerts;
  private final Clock clock;

  public CareCircleService(
      CareCircleMemberStore store,
      CustomerNamePort customerNames,
      MemberCascadePort cascade,
      MemberStatsPort memberStats,
      TodayAdherencePort todayAdherence,
      RefillAlertQueryPort refillAlerts,
      Clock clock) {
    this.store = store;
    this.customerNames = customerNames;
    this.cascade = cascade;
    this.memberStats = memberStats;
    this.todayAdherence = todayAdherence;
    this.refillAlerts = refillAlerts;
    this.clock = clock;
  }

  /** Public for STORY-001 medicines POST — creates SELF if missing. */
  @Transactional
  public MemberRecord ensureSelf(UUID customerId) {
    return store
        .findSelf(customerId)
        .orElseGet(
            () -> {
              Instant now = clock.instant();
              String name = customerNames.nameFor(customerId);
              if (name == null || name.isBlank()) {
                name = "Customer";
              }
              return store.insert(
                  new MemberRecord(
                      Ids.newId(),
                      customerId,
                      trimName(name),
                      0,
                      CareCircleRelationship.SELF.name(),
                      DEFAULT_AVATAR_EMOJI,
                      DEFAULT_AVATAR_COLOR,
                      true,
                      now,
                      now,
                      null));
            });
  }

  @Transactional
  public Map<String, Object> list(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    ensureSelf(customerId);
    List<MemberRecord> members = store.listByCustomer(customerId);
    List<Map<String, Object>> rows = new ArrayList<>(members.size());
    for (MemberRecord m : members) {
      MemberStatsPort.MemberListStats stats = memberStats.statsForMember(m.id());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("member_id", m.id());
      row.put("name", m.name());
      row.put("age", m.age());
      row.put("relationship", m.relationship());
      row.put("avatar_emoji", m.avatarEmoji());
      row.put("avatar_color", m.avatarColor());
      row.put("is_self", m.self());
      row.put("medicines_count", stats.medicinesCount());
      row.put("today_doses_total", stats.todayDosesTotal());
      row.put("today_doses_taken", stats.todayDosesTaken());
      row.put("today_adherence_pct", stats.todayAdherencePct());
      row.put("refill_alerts_count", stats.refillAlertsCount());
      rows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("members", rows);
    data.put("total_members", rows.size());
    data.put("can_add_more", rows.size() < MAX_MEMBERS);
    return data;
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    ensureSelf(customerId);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }
    String name = requireName(cmd.name());
    int age = requireAge(cmd.age());
    CareCircleRelationship relationship;
    try {
      relationship = CareCircleRelationship.parseFamily(cmd.relationship());
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }
    String emoji = resolveEmoji(cmd.avatarEmoji());
    String color = resolveColor(cmd.avatarColor(), true);

    if (store.countByCustomer(customerId) >= MAX_MEMBERS) {
      throw new AppException(
          "CARE_CIRCLE_LIMIT_REACHED", "Care circle already has the maximum of 10 members", 400);
    }

    Instant now = clock.instant();
    MemberRecord saved =
        store.insert(
            new MemberRecord(
                Ids.newId(),
                customerId,
                name,
                age,
                relationship.name(),
                emoji,
                color,
                false,
                now,
                now,
                null));
    return toCreateView(saved);
  }

  @Transactional
  public Map<String, Object> update(MedmatePrincipal principal, UUID memberId, UpdateCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord existing = requireOwnedMember(memberId, customerId);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }

    String name = cmd.name() == null ? existing.name() : requireName(cmd.name());
    int age = cmd.age() == null ? existing.age() : requireAge(cmd.age());
    String relationship = existing.relationship();
    if (cmd.relationship() != null) {
      if (existing.self()) {
        throw new AppException(
            "VALIDATION_ERROR", "Cannot change relationship of SELF member", 400);
      }
      try {
        relationship = CareCircleRelationship.parseFamily(cmd.relationship()).name();
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
    }
    String emoji =
        cmd.avatarEmoji() == null ? existing.avatarEmoji() : resolveEmoji(cmd.avatarEmoji());
    String color =
        cmd.avatarColor() == null ? existing.avatarColor() : resolveColor(cmd.avatarColor(), false);

    Instant now = clock.instant();
    MemberRecord updated =
        store.update(
            new MemberRecord(
                existing.id(),
                existing.customerId(),
                name,
                age,
                relationship,
                emoji,
                color,
                existing.self(),
                existing.createdAt(),
                now,
                null));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member_id", updated.id());
    data.put("name", updated.name());
    data.put("age", updated.age());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord existing = requireOwnedMember(memberId, customerId);
    if (existing.self()) {
      throw new AppException("CANNOT_DELETE_SELF", "Self member cannot be deleted", 400);
    }
    Instant now = clock.instant();
    store.softDelete(memberId, now);
    MemberCascadePort.CascadeResult result = cascade.cascadeOnDelete(memberId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member_id", existing.id());
    data.put("name", existing.name());
    data.put("medicines_archived", result.medicinesArchived());
    data.put("reminders_cancelled", result.remindersCancelled());
    data.put("deleted_at", now);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = requireOwnedMember(memberId, customerId);
    TodayAdherencePort.TodayAdherence today = todayAdherence.todayForMember(memberId);

    Map<String, Object> memberView = new LinkedHashMap<>();
    memberView.put("member_id", member.id());
    memberView.put("name", member.name());
    memberView.put("age", member.age());
    memberView.put("relationship", member.relationship());
    memberView.put("avatar_emoji", member.avatarEmoji());

    Map<String, Object> todayView = new LinkedHashMap<>();
    todayView.put("total_doses", today.totalDoses());
    todayView.put("taken", today.taken());
    todayView.put("skipped", today.skipped());
    todayView.put("missed", today.missed());
    todayView.put("upcoming", today.upcoming());
    todayView.put("adherence_pct", today.adherencePct());

    List<Map<String, Object>> alerts = new ArrayList<>();
    for (RefillAlertQueryPort.RefillAlert alert : refillAlerts.refillAlerts(memberId)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("medicine_name", alert.medicineName());
      row.put("units_in_hand", alert.unitsInHand());
      Integer approx = alert.approxDaysLeft();
      row.put("approx_days_left", approx == null ? Integer.valueOf(0) : approx);
      alerts.add(row);
    }

    List<Map<String, Object>> medicines = new ArrayList<>();
    for (RefillAlertQueryPort.MedicineSummary med : refillAlerts.medicines(memberId)) {
      List<Map<String, Object>> slots = new ArrayList<>();
      for (RefillAlertQueryPort.DoseSlot slot : med.doseSlots()) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("slot", slot.slot());
        s.put("reminder_time", slot.reminderTime());
        slots.add(s);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("medicine_id", med.medicineId());
      row.put("medicine_name", med.medicineName());
      row.put("dose", med.dose());
      row.put("form", med.form());
      row.put("dose_slots", slots);
      row.put("is_active", med.active());
      medicines.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView);
    data.put("today", todayView);
    data.put("this_week_adherence_pct", refillAlerts.thisWeekAdherencePct(memberId));
    data.put("refill_alerts", alerts);
    data.put("medicines", medicines);
    return data;
  }

  private MemberRecord requireOwnedMember(UUID memberId, UUID customerId) {
    MemberRecord member =
        store
            .findById(memberId)
            .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", 404));
    if (!member.customerId().equals(customerId)) {
      throw new AppException(
          "MEMBER_ACCESS_DENIED", "Member does not belong to this customer", 403);
    }
    return member;
  }

  private static UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private static String requireName(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    return trimName(raw);
  }

  private static String trimName(String raw) {
    String trimmed = raw.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name max length is 100", 400);
    }
    return trimmed;
  }

  private static int requireAge(Integer age) {
    if (age == null || age < 0 || age > 120) {
      throw new AppException("INVALID_AGE", "Age must be between 0 and 120", 400);
    }
    return age;
  }

  private static String resolveEmoji(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_AVATAR_EMOJI;
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 10) {
      throw new AppException("VALIDATION_ERROR", "avatar_emoji max length is 10", 400);
    }
    return trimmed;
  }

  private static String resolveColor(String raw, boolean useDefaultWhenBlank) {
    if (raw == null || raw.isBlank()) {
      if (useDefaultWhenBlank) {
        return DEFAULT_AVATAR_COLOR;
      }
      throw new AppException(
          "INVALID_AVATAR_COLOR", "avatar_color must be a hex color (#RRGGBB)", 400);
    }
    String trimmed = raw.trim();
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      throw new AppException(
          "INVALID_AVATAR_COLOR", "avatar_color must be a hex color (#RRGGBB)", 400);
    }
    return trimmed;
  }

  private static Map<String, Object> toCreateView(MemberRecord m) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member_id", m.id());
    data.put("name", m.name());
    data.put("age", m.age());
    data.put("relationship", m.relationship());
    data.put("avatar_emoji", m.avatarEmoji());
    data.put("avatar_color", m.avatarColor());
    data.put("is_self", m.self());
    data.put("created_at", m.createdAt());
    return data;
  }

  public record CreateCommand(
      String name, Integer age, String relationship, String avatarEmoji, String avatarColor) {}

  public record UpdateCommand(
      String name, Integer age, String relationship, String avatarEmoji, String avatarColor) {}
}
