package com.nammamedmate.teleconsult.application.port.out;

import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.ConsultStatusEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ConsultStore {

  void insert(Consult consult);

  void update(Consult consult);

  void insertStatusEvent(ConsultStatusEvent event);

  Optional<Consult> findById(UUID id);

  Optional<Consult> findByIdForCustomer(UUID id, UUID customerId);

  long countActiveByCustomer(UUID customerId);

  boolean hasActiveCartModeConsult(UUID cartId);

  Page list(ListFilter filter);

  /** NOW-slot REQUESTED consults with no doctor, ordered by created_at ASC. */
  int countQueuedNowAheadOrEqual(Instant createdAt);

  /** Rolling 7-day average completed call duration in minutes; empty when no history. */
  Optional<Integer> rollingAvgCallDurationMinutes();

  List<Consult> findDueForAutoCancel(Instant deadlineBefore);

  /** Unassigned scheduled consults whose slot time has arrived. */
  default List<Consult> findDueForScheduledAssign(Instant now) {
    return List.of();
  }

  /** Active queue rows sorted IN_CALL → CALLING → DOCTOR_REVIEWING → REQUESTED, then created_at. */
  List<QueueItem> listActiveQueue();

  Map<String, Long> countActiveByStatus();

  AdminPage adminList(AdminListFilter filter);

  AdminDayStats adminDayStats(Instant rangeStart, Instant rangeEnd);

  long countRatingsByDoctor(UUID doctorId);

  DoctorPeriodStats doctorPeriodStats(UUID doctorId, Instant rangeStart, Instant rangeEnd);

  record ListFilter(UUID customerId, String status, int page, int limit) {}

  record ListItem(
      UUID consultId,
      Instant createdAt,
      String doctorName,
      String status,
      UUID ePrescriptionId,
      UUID cartId,
      boolean cartMode,
      Integer rating) {}

  record Page(List<ListItem> items, long total) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record QueueItem(
      UUID consultId,
      String status,
      String patientName,
      String patientPhone,
      String doctorName,
      List<String> medicinesRequested,
      Instant callStartedAt,
      Instant createdAt,
      boolean cartMode) {
    public QueueItem {
      medicinesRequested = medicinesRequested == null ? List.of() : List.copyOf(medicinesRequested);
    }
  }

  record AdminListFilter(
      Instant rangeStart,
      Instant rangeEnd,
      UUID doctorId,
      String status,
      Boolean cartMode,
      int page,
      int limit) {}

  record AdminListItem(
      UUID consultId,
      String patientName,
      String doctorName,
      String status,
      BigDecimal durationMinutes,
      boolean ePrescriptionIssued,
      boolean cartMode,
      Integer rating,
      Instant createdAt,
      Instant completedAt) {}

  record AdminPage(List<AdminListItem> items, long total) {
    public AdminPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record AdminDayStats(
      long totalToday,
      long completed,
      long inProgress,
      long cancelled,
      BigDecimal avgDurationMinutes,
      BigDecimal avgRating,
      long pendingRating) {}

  record DoctorPeriodStats(
      long consultsPeriod,
      BigDecimal avgCallDurationMinutes,
      long ePrescriptionsIssued,
      long adviceOnlyConsults,
      BigDecimal patientSatisfactionRate,
      List<Map<String, Object>> consultsByDay) {
    public DoctorPeriodStats {
      consultsByDay = consultsByDay == null ? List.of() : List.copyOf(consultsByDay);
    }
  }
}
