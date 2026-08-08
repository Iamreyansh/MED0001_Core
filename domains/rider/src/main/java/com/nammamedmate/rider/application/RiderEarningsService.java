package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.LifetimeTotals;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.PeriodTotals;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.TripView;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.rider.domain.IncentiveRules;
import com.nammamedmate.rider.domain.PayoutCycle;
import com.nammamedmate.rider.domain.PayoutCycle.Window;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderEarningsService {

  private final RiderStore riders;
  private final RiderTripEarningsStore earnings;
  private final RiderPayoutStore payouts;
  private final PlatformPricingConfigStore config;
  private final Clock clock;

  public RiderEarningsService(
      RiderStore riders,
      RiderTripEarningsStore earnings,
      RiderPayoutStore payouts,
      PlatformPricingConfigStore config,
      Clock clock) {
    this.riders = riders;
    this.earnings = earnings;
    this.payouts = payouts;
    this.config = config;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> dashboard(MedmatePrincipal principal) {
    RiderRecord rider = requireRider(principal);
    Instant now = clock.instant();
    LocalDate today = PayoutCycle.istDate(now);
    Window week = PayoutCycle.current(now);
    PeriodTotals todayT = earnings.sumForRider(rider.id(), today, today);
    PeriodTotals weekT = earnings.sumForRider(rider.id(), week.from(), week.to());
    LifetimeTotals life = earnings.lifetime(rider.id());
    int streakDays = rider.dailyStreakDays();
    int required = IncentiveRules.streakDaysRequired(config);
    long bonus = IncentiveRules.streakBonusPaise(config);
    long carry = riders.payoutCarryForwardPaise(rider.id());
    long estimated =
        weekT.totalPaise()
            + carry
            + (riders.streakBonusPending(rider.id()) || streakDays >= required ? bonus : 0L)
            - rider.codInHandPaise();
    if (estimated < 0) {
      estimated = 0;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", rider.id().toString());
    data.put("today", periodMap(todayT));
    Map<String, Object> thisWeek = periodMap(weekT);
    thisWeek.put("cycle_from", week.from().toString());
    thisWeek.put("cycle_to", week.to().toString());
    data.put("this_week", thisWeek);
    Map<String, Object> lifetime = new LinkedHashMap<>();
    lifetime.put("total_earnings", CodFloatLimits.paiseToRupees(life.totalEarningsPaise()));
    lifetime.put("total_trips", life.totalTrips());
    data.put("lifetime", lifetime);
    data.put("wallet_balance", CodFloatLimits.paiseToRupees(rider.earningsWalletBalancePaise()));
    Map<String, Object> streak = new LinkedHashMap<>();
    streak.put("current_days", streakDays);
    streak.put("streak_bonus_at_days", required);
    streak.put("streak_bonus_amount", CodFloatLimits.paiseToRupees(bonus));
    streak.put("days_remaining_for_bonus", Math.max(0, required - streakDays));
    data.put("streak", streak);
    Map<String, Object> next = new LinkedHashMap<>();
    next.put("estimated_amount", CodFloatLimits.paiseToRupees(estimated));
    next.put("date", PayoutCycle.nextPayoutDate(week).toString());
    next.put("cod_deduction_expected", CodFloatLimits.paiseToRupees(rider.codInHandPaise()));
    data.put("next_payout", next);
    return data;
  }

  @Transactional(readOnly = true)
  public TripsResult trips(
      MedmatePrincipal principal, Integer page, Integer limit, String from, String to) {
    RiderRecord rider = requireRider(principal);
    PageRequest pr = PageRequest.normalize(page, limit, null, null);
    LocalDate fromD = parseDate(from);
    LocalDate toD = parseDate(to);
    long total = earnings.countTrips(rider.id(), fromD, toD);
    List<TripView> rows = earnings.listTrips(rider.id(), fromD, toD, pr.offset(), pr.limit());
    List<Map<String, Object>> trips = new ArrayList<>();
    for (TripView t : rows) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("order_id", t.orderId().toString());
      m.put("order_number", t.orderNumber());
      m.put("pickup_pharmacy", t.pickupPharmacy());
      m.put("delivery_area", t.deliveryArea());
      m.put(
          "distance_km",
          t.distanceKm() == null
              ? 0.0
              : t.distanceKm().setScale(1, java.math.RoundingMode.HALF_UP).doubleValue());
      m.put("duration_minutes", t.durationMinutes());
      m.put("base_pay", CodFloatLimits.paiseToRupees(t.basePayPaise()));
      m.put("tip", CodFloatLimits.paiseToRupees(t.tipPaise()));
      m.put("incentive_bonus", CodFloatLimits.paiseToRupees(t.incentiveBonusPaise()));
      m.put("total_earned", CodFloatLimits.paiseToRupees(t.totalPaise()));
      m.put("on_time", t.onTime());
      m.put("customer_rating", t.customerRating());
      m.put("completed_at", t.completedAt() == null ? null : t.completedAt().toString());
      trips.add(m);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("trips", trips);
    return new TripsResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional(readOnly = true)
  public LedgerResult adminLedger(
      MedmatePrincipal principal,
      UUID riderId,
      Integer page,
      Integer limit,
      String from,
      String to) {
    requireFinanceRead(principal);
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    PageRequest pr = PageRequest.normalize(page, limit, null, null);
    LocalDate fromD = parseDate(from);
    LocalDate toD = parseDate(to);
    long total = payouts.countForRider(riderId, fromD, toD);
    List<Map<String, Object>> ledger = new ArrayList<>();
    for (var p : payouts.listForRider(riderId, fromD, toD, pr.offset(), pr.limit())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("payout_id", p.id().toString());
      m.put("cycle_from", p.cycleFrom().toString());
      m.put("cycle_to", p.cycleTo().toString());
      m.put("base_earnings", CodFloatLimits.paiseToRupees(p.baseEarningsPaise()));
      m.put("incentives", CodFloatLimits.paiseToRupees(p.incentivesPaise()));
      m.put("tips", CodFloatLimits.paiseToRupees(p.tipsPaise()));
      m.put("streak_bonus", CodFloatLimits.paiseToRupees(p.streakBonusPaise()));
      m.put("cod_deducted", CodFloatLimits.paiseToRupees(p.codDeductedPaise()));
      m.put("net_payout", CodFloatLimits.paiseToRupees(p.netPayoutPaise()));
      m.put("payout_status", p.status());
      m.put("payout_reference", p.payoutReference());
      m.put("released_at", p.releasedAt() == null ? null : p.releasedAt().toString());
      ledger.add(m);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", rider.id().toString());
    data.put("rider_name", rider.name());
    data.put("ledger", ledger);
    return new LedgerResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  private static Map<String, Object> periodMap(PeriodTotals t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("base", CodFloatLimits.paiseToRupees(t.basePaise()));
    m.put("incentives", CodFloatLimits.paiseToRupees(t.incentivesPaise()));
    m.put("tips", CodFloatLimits.paiseToRupees(t.tipsPaise()));
    m.put("total", CodFloatLimits.paiseToRupees(t.totalPaise()));
    m.put("trips", t.trips());
    return m;
  }

  private RiderRecord requireRider(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
    return riders
        .findById(principal.subject())
        .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
  }

  private static void requireFinanceRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return LocalDate.parse(raw.trim());
  }

  public record TripsResult(Map<String, Object> data, PaginationMeta meta) {
    public TripsResult {
      data = Map.copyOf(data);
    }
  }

  public record LedgerResult(Map<String, Object> data, PaginationMeta meta) {
    public LedgerResult {
      data = Map.copyOf(data);
    }
  }
}
