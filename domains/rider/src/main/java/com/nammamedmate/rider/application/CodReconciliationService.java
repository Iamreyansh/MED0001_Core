package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.CodCollectionStore;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionRecord;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionView;
import com.nammamedmate.rider.application.port.out.CodDepositConfirmedPort;
import com.nammamedmate.rider.application.port.out.CodDepositStore;
import com.nammamedmate.rider.application.port.out.CodDepositStore.BoardPage;
import com.nammamedmate.rider.application.port.out.CodDepositStore.CodBoardRow;
import com.nammamedmate.rider.application.port.out.CodDepositStore.DepositRecord;
import com.nammamedmate.rider.application.port.out.FinanceCodDailyReconciliationPort;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CodReconciliationService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Set<AuthRole> BOARD_ROLES =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);
  private static final Set<AuthRole> CONFIRM_ROLES =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_FINANCE);
  private static final Set<String> DEPOSIT_MODES = Set.of("BRANCH", "UPI");

  private final RiderStore riders;
  private final CodCollectionStore collections;
  private final CodDepositStore deposits;
  private final PlatformPricingConfigStore pricingConfig;
  private final RiderFleetStore fleet;
  private final OutboxPublisher outbox;
  private final Clock clock;
  private final CodDepositConfirmedPort depositConfirmed;
  private final FinanceCodDailyReconciliationPort financeDaily;

  public CodReconciliationService(
      RiderStore riders,
      CodCollectionStore collections,
      CodDepositStore deposits,
      PlatformPricingConfigStore pricingConfig,
      RiderFleetStore fleet,
      OutboxPublisher outbox,
      Clock clock) {
    this(riders, collections, deposits, pricingConfig, fleet, outbox, clock, null, null);
  }

  @Autowired
  public CodReconciliationService(
      RiderStore riders,
      CodCollectionStore collections,
      CodDepositStore deposits,
      PlatformPricingConfigStore pricingConfig,
      RiderFleetStore fleet,
      OutboxPublisher outbox,
      Clock clock,
      @Nullable CodDepositConfirmedPort depositConfirmed,
      @Nullable FinanceCodDailyReconciliationPort financeDaily) {
    this.riders = riders;
    this.collections = collections;
    this.deposits = deposits;
    this.pricingConfig = pricingConfig;
    this.fleet = fleet;
    this.outbox = outbox;
    this.clock = clock;
    this.depositConfirmed =
        depositConfirmed == null ? (id, riderId, amount) -> {} : depositConfirmed;
    this.financeDaily = financeDaily;
  }

  public record BoardResult(Map<String, Object> data, PaginationMeta meta) {
    public BoardResult {
      data = Map.copyOf(data);
    }
  }

  public long floatLimitPaise() {
    return CodFloatLimits.resolvePaise(pricingConfig);
  }

  public void assertCanAcceptCod(UUID riderId) {
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long limit = floatLimitPaise();
    if (!CodFloatLimits.canAcceptCod(rider.codInHandPaise(), limit)) {
      throw new AppException(
          "COD_LIMIT_EXCEEDED",
          "COD float limit exceeded; deposit cash before accepting COD orders",
          422);
    }
  }

  @Transactional
  public void recordCollection(UUID riderId, UUID orderId, long amountPaise, Instant collectedAt) {
    if (riderId == null || orderId == null || amountPaise <= 0) {
      return;
    }
    if (collections.findByOrderId(orderId).isPresent()) {
      return;
    }
    Instant now = collectedAt == null ? clock.instant() : collectedAt;
    riders
        .findById(riderId)
        .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long before = riders.findById(riderId).map(RiderRecord::codInHandPaise).orElse(0L);
    collections.insert(
        new CollectionRecord(Ids.newId(), riderId, orderId, amountPaise, now, null, false, now));
    long after = riders.adjustCodInHand(riderId, amountPaise, now);
    long limit = floatLimitPaise();
    if (!CodFloatLimits.isFloatRisk(before, limit) && CodFloatLimits.isFloatRisk(after, limit)) {
      publishFloatRisk(riderId, after, limit, now);
    }
  }

  @Transactional(readOnly = true)
  public BoardResult adminBoard(
      MedmatePrincipal principal, UUID zoneId, Boolean riskOnly, Integer page, Integer limit) {
    requireBoard(principal);
    boolean risk = Boolean.TRUE.equals(riskOnly);
    int p = page == null || page < 1 ? 1 : page;
    int lim = 20;
    if (limit != null && limit >= 1) {
      lim = Math.min(limit, 100);
    }
    long floatLimit = floatLimitPaise();
    Instant now = clock.instant();
    DayWindow day = istDay(now);

    BoardPage board = deposits.listBoard(zoneId, risk, floatLimit, p, lim);
    long depositedToday = deposits.sumDepositedTodayAll(day.start(), day.end());
    long pendingDeposit = deposits.sumPendingDepositRequests(day.start(), day.end());
    int riskCount = deposits.countFloatRiskRiders(floatLimit);
    long allInHand = deposits.sumCodInHandAll();

    List<Map<String, Object>> riderMaps = new ArrayList<>();
    for (CodBoardRow row : board.rows()) {
      long collected = collections.sumCollectedToday(row.riderId(), day.start(), day.end());
      long deposited = deposits.sumDepositedToday(row.riderId(), day.start(), day.end());
      int trips = fleet.countTripsToday(row.riderId(), day.startUtc(), day.endUtc());
      Instant lastDep = deposits.lastConfirmedDepositAt(row.riderId());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rider_id", row.riderId().toString());
      m.put("rider_name", row.riderName());
      m.put("zone_name", row.zoneName());
      m.put("trips_today", trips);
      m.put("cod_collected", CodFloatLimits.paiseToRupees(collected));
      m.put("cod_deposited", CodFloatLimits.paiseToRupees(deposited));
      m.put("cod_in_hand", CodFloatLimits.paiseToRupees(row.codInHandPaise()));
      m.put("deposit_status", depositStatus(row.codInHandPaise(), collected, deposited));
      m.put("risk_status", CodFloatLimits.riskStatus(row.codInHandPaise(), floatLimit));
      m.put("last_deposit_at", lastDep == null ? null : lastDep.toString());
      riderMaps.add(m);
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_cod_in_hand", CodFloatLimits.paiseToRupees(allInHand));
    summary.put("deposited_today", CodFloatLimits.paiseToRupees(depositedToday));
    summary.put("pending_deposit", CodFloatLimits.paiseToRupees(pendingDeposit));
    summary.put("float_risk_riders_count", riskCount);
    summary.put("cod_float_limit", CodFloatLimits.paiseToRupees(floatLimit));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("riders", riderMaps);
    return new BoardResult(data, PaginationMeta.of(p, lim, board.total()));
  }

  @Transactional
  public Map<String, Object> markDeposited(
      MedmatePrincipal principal,
      UUID riderId,
      Object amount,
      String depositedAtRaw,
      String referenceNumber,
      String notes) {
    requireConfirm(principal);
    if (riderId == null) {
      throw new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404);
    }
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long amountPaise = parsePositiveAmount(amount);
    if (amountPaise > rider.codInHandPaise()) {
      throw new AppException(
          "AMOUNT_EXCEEDS_IN_HAND", "Deposit amount exceeds rider COD in hand", 422);
    }
    if (referenceNumber == null) {
      throw new AppException("VALIDATION_ERROR", "reference_number is required", 400);
    }
    if (referenceNumber.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reference_number is required", 400);
    }
    String ref = referenceNumber.trim();
    Instant now = clock.instant();
    Instant depositedAt = parseInstant(depositedAtRaw, now);

    DepositRecord pending = deposits.findPendingByReference(riderId, ref).orElse(null);
    UUID depositId;
    if (pending != null) {
      depositId = pending.id();
      DepositRecord confirmed =
          new DepositRecord(
              pending.id(),
              pending.riderId(),
              amountPaise,
              pending.depositMode(),
              pending.referenceNumber(),
              "CONFIRMED",
              pending.submittedAt(),
              now,
              principal.subject(),
              depositedAt,
              notes,
              pending.createdAt(),
              now);
      deposits.update(confirmed);
    } else {
      if (deposits.referenceExists(ref)) {
        throw new AppException("DUPLICATE_REFERENCE", "Reference number already submitted", 409);
      }
      depositId = Ids.newId();
      deposits.insert(
          new DepositRecord(
              depositId,
              riderId,
              amountPaise,
              "UPI",
              ref,
              "CONFIRMED",
              now,
              now,
              principal.subject(),
              depositedAt,
              notes,
              now,
              now));
    }

    long after = riders.adjustCodInHand(riderId, -amountPaise, now);
    collections.markDepositedFifo(riderId, depositId, amountPaise);
    depositConfirmed.onDepositConfirmed(depositId, riderId, amountPaise);
    long limit = floatLimitPaise();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("deposit_id", depositId.toString());
    data.put("amount_confirmed", CodFloatLimits.paiseToRupees(amountPaise));
    data.put("cod_in_hand_after", CodFloatLimits.paiseToRupees(after));
    data.put("risk_status_after", CodFloatLimits.riskStatus(after, limit));
    data.put("confirmed_by", principal.subject().toString());
    data.put("confirmed_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> riderSummary(MedmatePrincipal principal) {
    requireRider(principal);
    UUID riderId = principal.subject();
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long limit = floatLimitPaise();
    Instant now = clock.instant();
    DayWindow day = istDay(now);
    long collected = collections.sumCollectedToday(riderId, day.start(), day.end());
    long deposited = deposits.sumDepositedToday(riderId, day.start(), day.end());
    long inHand = rider.codInHandPaise();
    long remaining = Math.max(0L, limit - inHand);

    List<Map<String, Object>> trips = new ArrayList<>();
    for (CollectionView v : collections.recentForRider(riderId, 20)) {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("order_id", v.orderId().toString());
      t.put("order_number", v.orderNumber());
      t.put("cod_amount", CodFloatLimits.paiseToRupees(v.codAmountPaise()));
      t.put("collected_at", v.collectedAt().toString());
      t.put("deposited", v.deposited());
      trips.add(t);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("collected_today", CodFloatLimits.paiseToRupees(collected));
    data.put("deposited_today", CodFloatLimits.paiseToRupees(deposited));
    data.put("in_hand", CodFloatLimits.paiseToRupees(inHand));
    data.put("limit", CodFloatLimits.paiseToRupees(limit));
    data.put("limit_remaining", CodFloatLimits.paiseToRupees(remaining));
    data.put("risk_status", CodFloatLimits.riskStatus(inHand, limit));
    data.put("can_accept_cod_orders", CodFloatLimits.canAcceptCod(inHand, limit));
    data.put("next_deposit_reminder_at", nextReminderAt(now).toString());
    data.put("recent_cod_trips", trips);
    return data;
  }

  @Transactional
  public Map<String, Object> depositRequest(
      MedmatePrincipal principal,
      Object amount,
      String depositMode,
      String referenceNumber,
      String notes) {
    requireRider(principal);
    UUID riderId = principal.subject();
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long amountPaise = parsePositiveAmount(amount);
    if (amountPaise > rider.codInHandPaise()) {
      throw new AppException("AMOUNT_EXCEEDS_IN_HAND", "Claimed deposit exceeds COD in hand", 422);
    }
    if (depositMode == null || !DEPOSIT_MODES.contains(depositMode.trim().toUpperCase())) {
      throw new AppException("INVALID_DEPOSIT_MODE", "deposit_mode not BRANCH or UPI", 422);
    }
    if (referenceNumber == null) {
      throw new AppException("VALIDATION_ERROR", "reference_number is required", 400);
    }
    if (referenceNumber.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reference_number is required", 400);
    }
    String ref = referenceNumber.trim();
    if (deposits.referenceExists(ref)) {
      throw new AppException("DUPLICATE_REFERENCE", "Reference number already submitted", 409);
    }
    Instant now = clock.instant();
    UUID id = Ids.newId();
    String mode = depositMode.trim().toUpperCase();
    deposits.insert(
        new DepositRecord(
            id,
            riderId,
            amountPaise,
            mode,
            ref,
            "PENDING_CONFIRMATION",
            now,
            null,
            null,
            null,
            notes,
            now,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("deposit_request_id", id.toString());
    data.put("rider_id", riderId.toString());
    data.put("amount", CodFloatLimits.paiseToRupees(amountPaise));
    data.put("deposit_mode", mode);
    data.put("reference_number", ref);
    data.put("status", "PENDING_CONFIRMATION");
    data.put("submitted_at", now.toString());
    data.put(
        "message", "Your deposit request has been submitted. Admin will confirm within 2 hours.");
    return data;
  }

  @Transactional
  public Map<String, Object> remind(MedmatePrincipal principal, UUID riderId, String message) {
    requireBoard(principal);
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    Instant now = clock.instant();
    String msg =
        message == null || message.isBlank()
            ? "You have Rs "
                + CodFloatLimits.paiseToRupees(rider.codInHandPaise())
                + " in COD cash. Please deposit today to avoid order restrictions."
            : message.trim();
    outbox.publish(
        DomainEvent.of(
            "rider.notification.cod_deposit_reminder",
            "rider",
            riderId,
            Map.of(
                "rider_id",
                riderId.toString(),
                "message",
                msg,
                "cod_in_hand_paise",
                rider.codInHandPaise(),
                "template",
                "RIDER_COD_DEPOSIT_REMINDER",
                "channels",
                List.of("PUSH", "SMS"),
                "sent_by",
                principal.subject().toString())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("notification_sent", true);
    data.put("sms_sent", true);
    data.put("sent_by", principal.subject().toString());
    data.put("sent_at", now.toString());
    return data;
  }

  /**
   * AC-007 / EPIC-012 STORY-006: daily 11 PM IST reconciliation. When finance port is wired
   * (apps/api), delegates to {@link FinanceCodDailyReconciliationPort}; otherwise publishes the
   * legacy outbox stub payload (unit tests).
   */
  @Transactional
  public void publishDailyReport() {
    Instant now = clock.instant();
    DayWindow day = istDay(now);
    if (financeDaily != null) {
      financeDaily.runForDate(day.date());
      return;
    }
    long limit = floatLimitPaise();
    long deposited = deposits.sumDepositedTodayAll(day.start(), day.end());
    long collected = collections.sumCollectedTodayAll(day.start(), day.end());
    int riskCount = deposits.countFloatRiskRiders(limit);
    List<CodBoardRow> rows = deposits.allForReport(limit);
    List<Map<String, Object>> ridersPayload = new ArrayList<>();
    long totalInHand = 0L;
    for (CodBoardRow r : rows) {
      totalInHand += r.codInHandPaise();
      ridersPayload.add(
          Map.of(
              "rider_id",
              r.riderId().toString(),
              "rider_name",
              r.riderName() == null ? "" : r.riderName(),
              "cod_in_hand_paise",
              r.codInHandPaise(),
              "risk_status",
              CodFloatLimits.riskStatus(r.codInHandPaise(), limit)));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("report_date", day.date().toString());
    payload.put("generated_at", now.toString());
    payload.put("total_cod_in_hand_paise", totalInHand);
    payload.put("collected_today_paise", collected);
    payload.put("deposited_today_paise", deposited);
    payload.put("float_risk_riders_count", riskCount);
    payload.put("cod_float_limit_paise", limit);
    payload.put("riders", ridersPayload);
    payload.put("channels", List.of("EMAIL"));
    payload.put("audience", "admin_finance");
    payload.put("stub_for", "EPIC-012/STORY-006");
    outbox.publish(
        DomainEvent.of("finance.cod.daily_reconciliation_report", "finance", Ids.newId(), payload));
  }

  private void publishFloatRisk(UUID riderId, long inHand, long limit, Instant now) {
    outbox.publish(
        DomainEvent.of(
            "rider.notification.cod_float_risk",
            "rider",
            riderId,
            Map.of(
                "rider_id",
                riderId.toString(),
                "cod_in_hand_paise",
                inHand,
                "cod_float_limit_paise",
                limit,
                "risk_status",
                "FLOAT_RISK",
                "template",
                "RIDER_COD_FLOAT_RISK",
                "channels",
                List.of("PUSH", "SMS"),
                "triggered_at",
                now.toString())));
  }

  private static String depositStatus(long inHand, long collectedToday, long depositedToday) {
    if (inHand <= 0 && depositedToday <= 0 && collectedToday <= 0) {
      return "NONE";
    }
    if (inHand <= 0) {
      return "COMPLETE";
    }
    if (depositedToday > 0) {
      return "PARTIAL";
    }
    return "PENDING";
  }

  private static long parsePositiveAmount(Object amount) {
    try {
      long paise = CodFloatLimits.rupeesToPaise(amount);
      if (paise <= 0) {
        throw new AppException("INVALID_AMOUNT", "Amount must be > 0", 422);
      }
      return paise;
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("INVALID_AMOUNT", "Amount must be > 0", 422);
    }
  }

  private static Instant parseInstant(String raw, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new AppException("VALIDATION_ERROR", "deposited_at is invalid", 400);
    }
  }

  private static Instant nextReminderAt(Instant now) {
    ZonedDateTime z = now.atZone(IST).withHour(20).withMinute(0).withSecond(0).withNano(0);
    if (!z.toInstant().isAfter(now)) {
      z = z.plusDays(1);
    }
    return z.toInstant();
  }

  private static DayWindow istDay(Instant now) {
    LocalDate date = now.atZone(IST).toLocalDate();
    Instant start = date.atStartOfDay(IST).toInstant();
    Instant end = date.plusDays(1).atStartOfDay(IST).toInstant();
    Instant startUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant endUtc = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return new DayWindow(date, start, end, startUtc, endUtc);
  }

  private record DayWindow(
      LocalDate date, Instant start, Instant end, Instant startUtc, Instant endUtc) {}

  private static void requireRider(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider authentication required", 403);
    }
  }

  private static void requireBoard(MedmatePrincipal principal) {
    if (principal == null || !BOARD_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireConfirm(MedmatePrincipal principal) {
    if (principal == null || !CONFIRM_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
