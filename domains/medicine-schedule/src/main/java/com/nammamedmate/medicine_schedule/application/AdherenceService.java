package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DailyCounts;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.AdherenceMath;
import com.nammamedmate.medicine_schedule.domain.DayAdherenceStatus;
import com.nammamedmate.medicine_schedule.domain.WeekAdherenceBand;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdherenceService {

  private static final ZoneId IST = ReminderRecalcService.IST;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final DateTimeFormatter WEEK_LABEL =
      DateTimeFormatter.ofPattern("MMM d", Locale.US);
  private static final int DEFAULT_WEEKS = 12;
  private static final int MAX_WEEKS = 52;
  private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

  private final DoseLogStore doseLogs;
  private final CareCircleMemberStore members;
  private final CareCircleService careCircle;
  private final ScheduleMedicineStore medicines;
  private final Clock clock;

  // ponytail: story midnight IST refresh of this_week_pct / current_streak_days; v1 recomputes on
  // read

  public AdherenceService(
      DoseLogStore doseLogs,
      CareCircleMemberStore members,
      CareCircleService careCircle,
      ScheduleMedicineStore medicines,
      Clock clock) {
    this.doseLogs = doseLogs;
    this.members = members;
    this.careCircle = careCircle;
    this.medicines = medicines;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    LocalDate today = todayIst();

    List<DailyCounts> all = doseLogs.dailyCountsForMember(member.id(), EPOCH, today);
    int currentStreakDays = currentStreak(all, today);
    int longestStreakDays = longestStreak(all);
    LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    TodayCounts week = doseLogs.countsForMemberBetween(member.id(), weekStart, today);
    Double thisWeekPct = AdherenceMath.pct(week.taken(), week.total());

    int totalDaysTracked = (int) all.stream().filter(d -> d.total() > 0).count();
    int takenAll = all.stream().mapToInt(DailyCounts::taken).sum();
    int scheduledAll = all.stream().mapToInt(DailyCounts::total).sum();
    Double allTimePct = AdherenceMath.pct(takenAll, scheduledAll);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView(member));
    data.put("this_week_pct", thisWeekPct);
    data.put("current_streak_days", currentStreakDays);
    data.put("longest_streak_days", longestStreakDays);
    data.put("total_days_tracked", totalDaysTracked);
    data.put("all_time_pct", allTimePct);
    data.put("monthly_adherence", monthlyAdherence(all));
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> calendar(MedmatePrincipal principal, UUID memberId, String monthRaw) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    YearMonth month = parseMonth(monthRaw);
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    Map<LocalDate, DailyCounts> byDate = new LinkedHashMap<>();
    for (DailyCounts d : doseLogs.dailyCountsForMember(member.id(), from, to)) {
      byDate.put(d.doseDate(), d);
    }

    List<Map<String, Object>> days = new ArrayList<>();
    int takenSum = 0;
    int scheduledSum = 0;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      DailyCounts c = byDate.get(d);
      int total = c == null ? 0 : c.total();
      int taken = c == null ? 0 : c.taken();
      int skipped = c == null ? 0 : c.skipped();
      int missed = c == null ? 0 : c.missed();
      Double pct = AdherenceMath.pct(taken, total);
      DayAdherenceStatus status = AdherenceMath.dayStatus(taken, total);
      if (total > 0) {
        takenSum += taken;
        scheduledSum += total;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", d.toString());
      row.put("total_doses", total);
      row.put("taken", taken);
      row.put("skipped", skipped);
      row.put("missed", missed);
      row.put("pct", pct);
      row.put("status", status.name());
      days.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView(member));
    data.put("month", month.format(MONTH));
    data.put("month_adherence_pct", AdherenceMath.pct(takenSum, scheduledSum));
    data.put("days", days);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> medicineAdherence(MedmatePrincipal principal, UUID medicineId) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord medicine =
        medicines
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (!medicine.customerId().equals(customerId)) {
      throw new AppException(
          "MEDICINE_ACCESS_DENIED", "Medicine does not belong to this customer", 403);
    }
    MemberRecord member = requireOwnedMember(medicine.memberId(), customerId);
    LocalDate today = todayIst();

    TodayCounts allTime = doseLogs.countsForMedicineBetween(medicineId, null, today);
    TodayCounts last7 = doseLogs.countsForMedicineBetween(medicineId, today.minusDays(6), today);
    TodayCounts last30 = doseLogs.countsForMedicineBetween(medicineId, today.minusDays(29), today);

    List<DailyCounts> daily = doseLogs.dailyCountsForMedicine(medicineId, null, today);
    List<String> missedDays = new ArrayList<>();
    for (int i = daily.size() - 1; i >= 0; i--) {
      DailyCounts d = daily.get(i);
      if (AdherenceMath.dayStatus(d.taken(), d.total()) == DayAdherenceStatus.MISSED) {
        missedDays.add(d.doseDate().toString());
      }
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", medicine.id());
    data.put("medicine_name", medicine.medicineName());
    data.put("member_name", member.name());
    data.put("last_7_days_pct", AdherenceMath.pct(last7.taken(), last7.total()));
    data.put("last_30_days_pct", AdherenceMath.pct(last30.taken(), last30.total()));
    data.put("all_time_pct", AdherenceMath.pct(allTime.taken(), allTime.total()));
    data.put("total_doses_scheduled", allTime.total());
    data.put("total_doses_taken", allTime.taken());
    data.put("total_missed", allTime.missed());
    data.put("total_skipped", allTime.skipped());
    data.put("missed_days_list", missedDays);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> chart(MedmatePrincipal principal, UUID memberId, Integer weeksRaw) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    int weeks = weeksRaw == null ? DEFAULT_WEEKS : weeksRaw;
    if (weeks < 1 || weeks > MAX_WEEKS) {
      throw new AppException("VALIDATION_ERROR", "weeks must be between 1 and 52", 400);
    }

    LocalDate today = todayIst();
    LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate earliest = currentWeekStart.minusWeeks(weeks - 1L);
    Map<LocalDate, DailyCounts> byDate = new LinkedHashMap<>();
    for (DailyCounts d : doseLogs.dailyCountsForMember(member.id(), earliest, today)) {
      byDate.put(d.doseDate(), d);
    }

    List<Map<String, Object>> rows = new ArrayList<>(weeks);
    for (int i = 0; i < weeks; i++) {
      LocalDate weekStart = currentWeekStart.minusWeeks(i);
      LocalDate weekEnd = weekStart.plusDays(6);
      int total = 0;
      int taken = 0;
      for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
        DailyCounts c = byDate.get(d);
        if (c != null) {
          total += c.total();
          taken += c.taken();
        }
      }
      Double pct = AdherenceMath.pct(taken, total);
      WeekAdherenceBand band = WeekAdherenceBand.fromPct(pct);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("week_start", weekStart.toString());
      row.put("week_end", weekEnd.toString());
      row.put("week_label", weekLabel(weekStart, weekEnd));
      row.put("adherence_pct", pct);
      row.put("total_doses", total);
      row.put("taken", taken);
      row.put("status", band.name());
      rows.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView(member));
    data.put("weeks", rows);
    return data;
  }

  /**
   * Consecutive PERFECT tracked days ending at the most recent tracked day on/before today.
   * NO_DOSES days are excluded from the chain; PARTIAL/MISSED breaks it.
   */
  static int currentStreak(List<DailyCounts> days, LocalDate today) {
    List<DailyCounts> tracked =
        days.stream()
            .filter(d -> d.total() > 0 && !d.doseDate().isAfter(today))
            .sorted(Comparator.comparing(DailyCounts::doseDate).reversed())
            .toList();
    int streak = 0;
    for (DailyCounts d : tracked) {
      if (AdherenceMath.dayStatus(d.taken(), d.total()) == DayAdherenceStatus.PERFECT) {
        streak++;
      } else {
        break;
      }
    }
    return streak;
  }

  /** Longest PERFECT run across tracked days; NO_DOSES gaps excluded (do not break). */
  static int longestStreak(List<DailyCounts> days) {
    List<DailyCounts> tracked =
        days.stream()
            .filter(d -> d.total() > 0)
            .sorted(Comparator.comparing(DailyCounts::doseDate))
            .toList();
    int best = 0;
    int run = 0;
    for (DailyCounts d : tracked) {
      if (AdherenceMath.dayStatus(d.taken(), d.total()) == DayAdherenceStatus.PERFECT) {
        run++;
        best = Math.max(best, run);
      } else {
        run = 0;
      }
    }
    return best;
  }

  private static List<Map<String, Object>> monthlyAdherence(List<DailyCounts> all) {
    Map<YearMonth, int[]> buckets = new LinkedHashMap<>();
    for (DailyCounts d : all) {
      if (d.total() <= 0) {
        continue;
      }
      YearMonth ym = YearMonth.from(d.doseDate());
      int[] agg = buckets.computeIfAbsent(ym, k -> new int[3]);
      agg[0] += d.taken();
      agg[1] += d.total();
      agg[2] += 1; // days tracked
    }
    List<YearMonth> months = new ArrayList<>(buckets.keySet());
    months.sort(Comparator.reverseOrder());
    List<Map<String, Object>> rows = new ArrayList<>(months.size());
    for (YearMonth ym : months) {
      int[] agg = buckets.get(ym);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", ym.format(MONTH));
      row.put("pct", AdherenceMath.pct(agg[0], agg[1]));
      row.put("days_tracked", agg[2]);
      rows.add(row);
    }
    return rows;
  }

  private static String weekLabel(LocalDate start, LocalDate end) {
    if (start.getMonth() == end.getMonth()) {
      return WEEK_LABEL.format(start) + "-" + end.getDayOfMonth();
    }
    return WEEK_LABEL.format(start) + "-" + WEEK_LABEL.format(end);
  }

  private static Map<String, Object> memberView(MemberRecord member) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("member_id", member.id());
    view.put("name", member.name());
    return view;
  }

  private YearMonth parseMonth(String monthRaw) {
    if (monthRaw == null || monthRaw.isBlank()) {
      return YearMonth.from(todayIst());
    }
    try {
      return YearMonth.parse(monthRaw.trim(), MONTH);
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "month must be YYYY-MM", 400);
    }
  }

  private LocalDate todayIst() {
    return LocalDate.ofInstant(clock.instant(), IST);
  }

  private MemberRecord resolveMember(UUID customerId, UUID memberId) {
    if (memberId == null) {
      return members.findSelf(customerId).orElseGet(() -> careCircle.ensureSelf(customerId));
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

  private static UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }
}
