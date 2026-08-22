package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore.RefillLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore.ScheduleShareTokenRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefillAlertService {

  public static final int SHARE_TOKEN_DAYS = 30;
  private static final String SHARE_BASE = "https://app.medmate.in/schedule/share/";
  private static final String SEARCH_APP = "medmate://search?query=";
  private static final String SEARCH_WEB = "https://app.medmate.in/search?query=";
  private static final DateTimeFormatter AMPM = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ScheduleMedicineStore medicines;
  private final CareCircleMemberStore members;
  private final CareCircleService careCircle;
  private final RefillAlertQueryPort refillAlerts;
  private final RefillLogStore refillLogs;
  private final ScheduleShareTokenStore shareTokens;
  private final NotificationDispatchPort notifications;
  private final DoseLogStore doseLogs;
  private final Clock clock;

  public RefillAlertService(
      ScheduleMedicineStore medicines,
      CareCircleMemberStore members,
      CareCircleService careCircle,
      RefillAlertQueryPort refillAlerts,
      RefillLogStore refillLogs,
      ScheduleShareTokenStore shareTokens,
      NotificationDispatchPort notifications,
      DoseLogStore doseLogs,
      Clock clock) {
    this.medicines = medicines;
    this.members = members;
    this.careCircle = careCircle;
    this.refillAlerts = refillAlerts;
    this.refillLogs = refillLogs;
    this.shareTokens = shareTokens;
    this.notifications = notifications;
    this.doseLogs = doseLogs;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listAlerts(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    List<RefillAlertQueryPort.RefillAlert> alerts = refillAlerts.refillAlerts(member.id());
    List<Map<String, Object>> rows = new ArrayList<>(alerts.size());
    for (RefillAlertQueryPort.RefillAlert a : alerts) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("medicine_id", a.medicineId());
      row.put("medicine_name", a.medicineName());
      row.put("strength", a.strength());
      row.put("form", a.form());
      row.put("units_in_hand", a.unitsInHand());
      row.put("refill_remind_at_units", a.refillRemindAtUnits());
      row.put("doses_per_day", a.dosesPerDay());
      row.put("approx_days_left", a.approxDaysLeft());
      row.put("master_medicine_id", a.masterMedicineId());
      row.put("can_order_online", a.canOrderOnline());
      row.put("alert_level", a.alertLevel());
      rows.add(row);
    }
    Map<String, Object> memberView = new LinkedHashMap<>();
    memberView.put("member_id", member.id());
    memberView.put("name", member.name());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView);
    data.put("refill_alerts_count", rows.size());
    data.put("alerts", rows);
    return data;
  }

  @Transactional
  public Map<String, Object> recordRefill(
      MedmatePrincipal principal, UUID medicineId, Integer unitsAdded, String refillDateRaw) {
    UUID customerId = requireCustomerId(principal);
    if (unitsAdded == null || unitsAdded <= 0) {
      throw new AppException("INVALID_UNITS", "units_added must be greater than 0", 400);
    }
    ScheduleMedicineRecord medicine = requireOwnedMedicine(medicineId, customerId);
    LocalDate refillDate = parseRefillDate(refillDateRaw);
    Instant now = clock.instant();
    int previous = medicine.unitsInHand();
    int next = previous + unitsAdded;
    medicines.update(
        new ScheduleMedicineRecord(
            medicine.id(),
            medicine.customerId(),
            medicine.memberId(),
            medicine.masterMedicineId(),
            medicine.medicineName(),
            medicine.strength(),
            medicine.dose(),
            medicine.form(),
            medicine.doseSlots(),
            medicine.foodInstruction(),
            medicine.durationType(),
            medicine.durationDays(),
            medicine.startedOnDate(),
            medicine.endedOnDate(),
            medicine.conditionName(),
            medicine.prescribedBy(),
            next,
            medicine.refillRemindAtUnits(),
            medicine.notes(),
            medicine.active(),
            medicine.createdAt(),
            now));
    refillLogs.insert(
        new RefillLogRecord(
            Ids.newId(), medicine.id(), customerId, unitsAdded, previous, next, refillDate, now));
    Integer approx = approxDaysLeft(next, medicine.doseSlots().size());
    boolean cleared = medicine.refillRemindAtUnits() <= 0 || next > medicine.refillRemindAtUnits();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", medicine.id());
    data.put("medicine_name", medicine.medicineName());
    data.put("units_added", unitsAdded);
    data.put("previous_units", previous);
    data.put("new_units_in_hand", next);
    data.put("approx_days_left", approx);
    data.put("refill_alert_cleared", cleared);
    data.put("refill_date", refillDate.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> orderOnline(MedmatePrincipal principal, UUID medicineId) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord medicine = requireOwnedMedicine(medicineId, customerId);
    String query = URLEncoder.encode(medicine.medicineName(), StandardCharsets.UTF_8);
    StringBuilder qs = new StringBuilder(query);
    if (medicine.masterMedicineId() != null) {
      qs.append("&master_id=").append(medicine.masterMedicineId());
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_name", medicine.medicineName());
    data.put("master_medicine_id", medicine.masterMedicineId());
    data.put("redirect_url", SEARCH_APP + qs);
    data.put("web_redirect_url", SEARCH_WEB + qs);
    return data;
  }

  @Transactional
  public Map<String, Object> createShareLink(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    Instant now = clock.instant();
    Instant expiresAt = now.plusSeconds(SHARE_TOKEN_DAYS * 24L * 3600L);
    String token = newUrlSafeToken();
    shareTokens.insert(
        new ScheduleShareTokenRecord(Ids.newId(), token, customerId, member.id(), expiresAt, now));
    int medicinesCount = medicines.listActiveByMember(member.id()).size();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("share_link", SHARE_BASE + token);
    data.put("token", token);
    data.put("expires_at", expiresAt);
    data.put("member_name", member.name());
    data.put("medicines_count", medicinesCount);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> viewSharedSchedule(String token) {
    if (token == null || token.isBlank()) {
      throw new AppException("SHARE_LINK_NOT_FOUND", "Share link not found", 404);
    }
    ScheduleShareTokenRecord share =
        shareTokens
            .findByToken(token.trim())
            .orElseThrow(
                () -> new AppException("SHARE_LINK_NOT_FOUND", "Share link not found", 404));
    Instant now = clock.instant();
    if (!share.expiresAt().isAfter(now)) {
      throw new AppException("SHARE_LINK_EXPIRED", "Share link has expired", 410);
    }
    MemberRecord member =
        members
            .findById(share.memberId())
            .orElseThrow(
                () -> new AppException("SHARE_LINK_NOT_FOUND", "Share link not found", 404));
    List<ScheduleMedicineRecord> active = medicines.listActiveByMember(member.id());
    List<Map<String, Object>> meds = new ArrayList<>(active.size());
    for (ScheduleMedicineRecord m : active) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("medicine_name", m.medicineName());
      row.put("dose", m.dose());
      row.put("form", m.form());
      row.put("food_instruction", m.foodInstruction());
      row.put("dose_slots", toAmPmSlots(m.doseSlots()));
      row.put("condition_name", m.conditionName());
      row.put("prescribed_by", m.prescribedBy());
      meds.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member_name", member.name());
    data.put("share_expires_at", share.expiresAt());
    data.put("medicines", meds);
    return data;
  }

  /** Nightly 00:30 IST supply decrement; idempotent per medicine per IST calendar day. */
  @Transactional
  public int runNightlySupplyDecrement() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
    Instant now = clock.instant();
    int changed = 0;
    for (ScheduleMedicineRecord m : medicines.listActiveWithSupplyTracking()) {
      int doses = m.doseSlots().size();
      if (doses <= 0) {
        continue;
      }
      if (refillLogs.existsNegativeOnDate(m.id(), today)) {
        continue;
      }
      int taken = doseLogs.countsForMedicineOn(m.id(), today).taken();
      int unrecorded = Math.max(0, doses - taken);
      if (unrecorded <= 0) {
        continue;
      }
      int before = m.unitsInHand();
      Integer after = medicines.decrementUnitsBy(m.id(), unrecorded, now).orElse(null);
      if (after == null) {
        continue;
      }
      refillLogs.insert(
          new RefillLogRecord(
              Ids.newId(), m.id(), m.customerId(), -unrecorded, before, after, today, now));
      changed++;
    }
    return changed;
  }

  /** Once-per-day push while medicine remains in refill alert. */
  @Transactional
  public int dispatchDailyRefillAlerts() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
    Instant now = clock.instant();
    int sent = 0;
    for (ScheduleMedicineRecord m : medicines.listRefillAlertsNeedingPush(today)) {
      notifications.notifyRefillAlert(
          m.customerId(), m.id(), m.unitsInHand(), m.refillRemindAtUnits());
      medicines.markRefillAlertPushedOn(m.id(), today, now);
      sent++;
    }
    return sent;
  }

  private MemberRecord resolveMember(UUID customerId, UUID memberId) {
    if (memberId == null) {
      return careCircle.ensureSelf(customerId);
    }
    return requireOwnedMember(memberId, customerId);
  }

  private MemberRecord requireOwnedMember(UUID memberId, UUID customerId) {
    MemberRecord member =
        members
            .findById(memberId)
            .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", 404));
    if (!member.customerId().equals(customerId)) {
      throw new AppException(
          "MEMBER_ACCESS_DENIED", "Member does not belong to this customer", 403);
    }
    return member;
  }

  private ScheduleMedicineRecord requireOwnedMedicine(UUID medicineId, UUID customerId) {
    ScheduleMedicineRecord medicine =
        medicines
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (!medicine.customerId().equals(customerId)) {
      throw new AppException(
          "MEDICINE_ACCESS_DENIED", "Medicine does not belong to this customer", 403);
    }
    return medicine;
  }

  private LocalDate parseRefillDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "refill_date must be YYYY-MM-DD", 400);
    }
  }

  private static Integer approxDaysLeft(int units, int dosesPerDay) {
    if (dosesPerDay <= 0) {
      return null;
    }
    return units / dosesPerDay;
  }

  private static List<Map<String, Object>> toAmPmSlots(List<DoseSlot> slots) {
    List<Map<String, Object>> views = new ArrayList<>(slots.size());
    for (DoseSlot s : slots) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slot", s.slot());
      row.put("time", formatAmPm(s.reminderTime()));
      views.add(row);
    }
    return views;
  }

  private static String formatAmPm(String hhMm) {
    LocalTime t = LocalTime.parse(hhMm);
    return AMPM.format(t);
  }

  static String newUrlSafeToken() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    // 24 bytes → 32 chars URL-safe Base64 without padding
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }
}
