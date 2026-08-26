package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminDayStats;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminListItem;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminPage;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.DoctorPeriodStats;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.Page;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.QueueItem;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.ConsultStatusEvent;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsultSessionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:40:00Z");
  private static final UUID ADMIN = UUID.fromString("a1000001-0000-4000-8000-0000000000a1");
  private static final UUID CUSTOMER = UUID.fromString("c1000001-0000-4000-8000-0000000000c1");
  private static final MedmatePrincipal OPS =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal CUSTOMER_P =
      new MedmatePrincipal(CUSTOMER, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  private FakeConsultStore consultStore;
  private FakeDoctorStore doctorStore;
  private RecordingNotifications notifications;
  private ConsultSessionService session;
  private ConsultService consultService;

  @BeforeEach
  void setUp() {
    consultStore = new FakeConsultStore();
    doctorStore = new FakeDoctorStore();
    notifications = new RecordingNotifications();
    RateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    session = new ConsultSessionService(consultStore, doctorStore, notifications, limiter, clock);
    consultService =
        new ConsultService(
            consultStore, doctorStore, (cartId, customerId) -> true, notifications, limiter, clock);
  }

  @Test
  void ac_invalidSkipToInCall() {
    UUID id = insert(Consult.STATUS_REQUESTED, null, false, null);
    assertThatThrownBy(() -> session.updateStatus(OPS, id, "IN_CALL", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
  }

  @Test
  void ac_eprescriptionRequired() {
    UUID id = insert(Consult.STATUS_IN_CALL, NOW.minusSeconds(300), false, null);
    assertThatThrownBy(() -> session.updateStatus(OPS, id, "COMPLETED", "done", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EPRESCRIPTION_REQUIRED");
  }

  @Test
  void ac_inCallSetsCallStartedAt() {
    UUID id = insert(Consult.STATUS_CALLING, null, false, null);
    Map<String, Object> data = session.updateStatus(OPS, id, "IN_CALL", "answered", null, null);
    assertThat(data.get("call_started_at")).isEqualTo(NOW);
    assertThat(data.get("previous_status")).isEqualTo("CALLING");
    assertThat(consultStore.byId.get(id).callStartedAt()).isEqualTo(NOW);
    assertThat(consultStore.events).hasSize(1);
    assertThat(notifications.statusUpdates).containsExactly("IN_CALL");
  }

  @Test
  void ac_completedBumpsDoctorTotals() {
    TeleconsultDoctor doctor = doctor(null, 10, 2);
    doctorStore.insert(doctor);
    UUID id =
        insertWithDoctor(
            Consult.STATUS_IN_CALL, doctor.id(), NOW.minusSeconds(420), false, Ids.newId());
    Map<String, Object> data =
        session.updateStatus(OPS, id, "COMPLETED", null, null, "internal note");
    assertThat(data.get("status")).isEqualTo("COMPLETED");
    TeleconsultDoctor updated = doctorStore.byId.get(doctor.id());
    assertThat(updated.totalConsults()).isEqualTo(11);
    assertThat(updated.consultsToday()).isEqualTo(3);
    assertThat(updated.lastAssignedAt()).isEqualTo(NOW);
    assertThat(consultStore.byId.get(id).durationMinutes())
        .isEqualByComparingTo(new BigDecimal("7.00"));
    assertThat(consultStore.byId.get(id).clinicalNotes()).isEqualTo("internal note");
  }

  @Test
  void completeWithoutCallForcesAdviceOnly() {
    TeleconsultDoctor doctor = doctor(null, 0, 0);
    doctorStore.insert(doctor);
    UUID id = insertWithDoctor(Consult.STATUS_IN_CALL, doctor.id(), null, false, null);
    session.updateStatus(OPS, id, "COMPLETED", "unreachable", true, null);
    Consult done = consultStore.byId.get(id);
    assertThat(done.adviceOnly()).isTrue();
    assertThat(done.durationMinutes()).isEqualByComparingTo(new BigDecimal("0.00"));
  }

  @Test
  void ac_rateUpdatesRunningAverage() {
    TeleconsultDoctor doctor = doctor(new BigDecimal("4.60"), 50, 0);
    doctorStore.insert(doctor);
    UUID id = insertCompleted(doctor.id(), false);
    Map<String, Object> rated = consultService.rate(CUSTOMER_P, id, 5, "Great");
    assertThat(rated.get("rating")).isEqualTo(5);
    assertThat(doctorStore.byId.get(doctor.id()).avgRating())
        .isEqualByComparingTo(new BigDecimal("4.61"));
  }

  @Test
  void ac_rateNotCompleted() {
    UUID id = insert(Consult.STATUS_IN_CALL, NOW, false, null);
    assertThatThrownBy(() -> consultService.rate(CUSTOMER_P, id, 5, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_COMPLETED");
  }

  @Test
  void ac_alreadyRated() {
    UUID id = insertCompleted(null, true);
    assertThatThrownBy(() -> consultService.rate(CUSTOMER_P, id, 4, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_RATED");
  }

  @Test
  void ac_queueOrder() {
    Instant t0 = NOW.minusSeconds(800);
    Instant t1 = NOW.minusSeconds(700);
    Instant t2 = NOW.minusSeconds(600);
    Instant t3 = NOW.minusSeconds(500);
    consultStore.insert(queueConsult("REQUESTED", t3));
    consultStore.insert(queueConsult("DOCTOR_REVIEWING", t2));
    consultStore.insert(queueConsult("CALLING", t1));
    consultStore.insert(queueConsult("IN_CALL", t0));
    Map<String, Object> data = session.queue(OPS);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pending = (List<Map<String, Object>>) data.get("pending_list");
    assertThat(pending)
        .extracting(m -> m.get("status"))
        .containsExactly("IN_CALL", "CALLING", "DOCTOR_REVIEWING", "REQUESTED");
    @SuppressWarnings("unchecked")
    Map<String, Object> counts = (Map<String, Object>) data.get("status_counts");
    assertThat(counts.get("total_active")).isEqualTo(4L);
  }

  @Test
  void adminListAndValidationBranches() {
    consultStore.dayStats =
        new AdminDayStats(1, 1, 0, 0, new BigDecimal("6.3"), new BigDecimal("4.6"), 0);
    consultStore.adminItems =
        List.of(
            new AdminListItem(
                Ids.newId(),
                "Ravi",
                "Dr A",
                "COMPLETED",
                new BigDecimal("7.00"),
                true,
                true,
                5,
                NOW,
                NOW));
    var result = session.list(OPS, "2026-07-24", null, "COMPLETED", true, 1, 20);
    assertThat(result.data().get("stats")).isInstanceOf(Map.class);
    assertThat(result.meta().total()).isEqualTo(1);

    assertThatThrownBy(() -> session.list(OPS, "bad", null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> session.updateStatus(OPS, Ids.newId(), "CALLING", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_FOUND");
    assertThatThrownBy(
            () -> session.updateStatus(CUSTOMER_P, Ids.newId(), "CALLING", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> session.updateStatus(OPS, Ids.newId(), null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> session.updateStatus(OPS, Ids.newId(), "ALL", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID id = insert(Consult.STATUS_REQUESTED, null, false, null);
    assertThatThrownBy(
            () -> session.updateStatus(OPS, id, "CANCELLED", "x".repeat(501), null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    session.updateStatus(OPS, id, "CANCELLED", "ok", null, null);
    RateLimiter openLimiter = mock(RateLimiter.class);
    when(openLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    ConsultService ratingSvc =
        new ConsultService(
            consultStore,
            doctorStore,
            (cartId, customerId) -> true,
            notifications,
            openLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, id, 0, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_COMPLETED");
    UUID completed = insertCompleted(null, false);
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, completed, 9, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RATING");
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, completed, 0, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RATING");
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, completed, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RATING");
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, completed, 5, "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(ratingSvc.rate(CUSTOMER_P, completed, 1, null).get("rating")).isEqualTo(1);
    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(limited.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(3);
    ConsultSessionService limitedSession =
        new ConsultSessionService(
            consultStore, doctorStore, notifications, limited, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> limitedSession.queue(OPS))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    // blank clinical / blank notes / default date / invalid status filter / page defaults
    UUID calling = insert(Consult.STATUS_CALLING, null, false, null);
    session.updateStatus(OPS, calling, "IN_CALL", "  ", null, "  ");
    consultStore.dayStats = new AdminDayStats(0, 0, 0, 0, null, null, 0);
    session.list(OPS, null, null, null, null, null, null);
    session.list(OPS, "  ", null, "  ", null, 1, 20);
    session.list(OPS, null, null, "COMPLETED", null, -1, 500);
    session.list(OPS, null, null, "COMPLETED", null, 2, 0);
    assertThatThrownBy(() -> session.list(OPS, null, null, "NOPE", null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> session.updateStatus(null, calling, "CANCELLED", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> session.updateStatus(OPS, calling, "  ", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> session.updateStatus(OPS, calling, "FOO", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // COMPLETED with no doctor / missing doctor row
    UUID noDoc = insert(Consult.STATUS_IN_CALL, NOW.minusSeconds(30), true, null);
    session.updateStatus(OPS, noDoc, "COMPLETED", null, true, null);
    UUID orphan =
        insertWithDoctor(Consult.STATUS_IN_CALL, Ids.newId(), NOW.minusSeconds(60), true, null);
    session.updateStatus(OPS, orphan, "COMPLETED", null, true, "note");

    // rate without doctor + null feedback
    UUID noDoctorCompleted = insertCompleted(null, false);
    assertThat(ratingSvc.rate(CUSTOMER_P, noDoctorCompleted, 5, null).get("rating")).isEqualTo(5);

    // rate with blank feedback and doctor missing from store
    UUID ratedId = insertCompleted(Ids.newId(), false);
    assertThat(ratingSvc.rate(CUSTOMER_P, ratedId, 4, "  ").get("rating")).isEqualTo(4);

    TeleconsultDoctor freshDoc = doctor(null, 0, 0);
    doctorStore.insert(freshDoc);
    UUID firstRate = insertCompleted(freshDoc.id(), false);
    assertThat(ratingSvc.rate(CUSTOMER_P, firstRate, 5, "ok").get("rating")).isEqualTo(5);
    UUID secondRate = insertCompleted(freshDoc.id(), false);
    assertThat(ratingSvc.rate(CUSTOMER_P, secondRate, 3, null).get("rating")).isEqualTo(3);

    UUID ratedOnly = insertCompleted(null, false);
    Consult ratedRow = consultStore.byId.get(ratedOnly);
    consultStore.update(
        new Consult(
            ratedRow.id(),
            ratedRow.customerId(),
            ratedRow.doctorId(),
            ratedRow.patientName(),
            ratedRow.patientPhone(),
            ratedRow.slotType(),
            ratedRow.scheduledAt(),
            ratedRow.symptoms(),
            ratedRow.medicinesNeedingRx(),
            ratedRow.cartId(),
            ratedRow.cartMode(),
            ratedRow.reason(),
            ratedRow.status(),
            ratedRow.callStartedAt(),
            ratedRow.callEndedAt(),
            ratedRow.durationMinutes(),
            ratedRow.ePrescriptionId(),
            ratedRow.adviceOnly(),
            ratedRow.clinicalNotes(),
            4,
            null,
            null,
            ratedRow.autoCancelledReason(),
            ratedRow.createdAt(),
            ratedRow.updatedAt(),
            ratedRow.deletedAt()));
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, ratedOnly, 3, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_RATED");

    // ALREADY_RATED via ratedAt only
    UUID ratedAtOnly = insertCompleted(null, false);
    Consult row = consultStore.byId.get(ratedAtOnly);
    consultStore.update(
        new Consult(
            row.id(),
            row.customerId(),
            row.doctorId(),
            row.patientName(),
            row.patientPhone(),
            row.slotType(),
            row.scheduledAt(),
            row.symptoms(),
            row.medicinesNeedingRx(),
            row.cartId(),
            row.cartMode(),
            row.reason(),
            row.status(),
            row.callStartedAt(),
            row.callEndedAt(),
            row.durationMinutes(),
            row.ePrescriptionId(),
            row.adviceOnly(),
            row.clinicalNotes(),
            null,
            null,
            NOW,
            row.autoCancelledReason(),
            row.createdAt(),
            row.updatedAt(),
            row.deletedAt()));
    assertThatThrownBy(() -> ratingSvc.rate(CUSTOMER_P, ratedAtOnly, 3, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_RATED");

    assertThat(new ConsultStore.AdminPage(null, 0).items()).isEmpty();
    assertThat(new ConsultStore.DoctorPeriodStats(0, null, 0, 0, null, null).consultsByDay())
        .isEmpty();
    assertThat(
            new ConsultStore.QueueItem(
                    Ids.newId(), "REQUESTED", "P", null, null, null, null, NOW, false)
                .medicinesRequested())
        .isEmpty();
    assertThat(new ConsultSessionService.AdminListResult(null, PaginationMeta.of(1, 20, 0)).data())
        .isEmpty();
  }

  private UUID insert(String status, Instant callStarted, boolean advice, UUID eRx) {
    return insertWithDoctor(status, null, callStarted, advice, eRx);
  }

  private UUID insertWithDoctor(
      String status, UUID doctorId, Instant callStarted, boolean advice, UUID eRx) {
    UUID id = Ids.newId();
    consultStore.insert(
        new Consult(
            id,
            CUSTOMER,
            doctorId,
            "Ravi",
            "+91-9",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(new Consult.MedicineNeed("Metformin", "REFILL")),
            null,
            false,
            "GENERAL",
            status,
            callStarted,
            null,
            null,
            eRx,
            advice,
            null,
            null,
            null,
            null,
            null,
            NOW.minusSeconds(600),
            NOW.minusSeconds(600),
            null));
    return id;
  }

  private UUID insertCompleted(UUID doctorId, boolean alreadyRated) {
    UUID id = Ids.newId();
    consultStore.insert(
        new Consult(
            id,
            CUSTOMER,
            doctorId,
            "Ravi",
            "+91-9",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            false,
            "GENERAL",
            Consult.STATUS_COMPLETED,
            NOW.minusSeconds(600),
            NOW.minusSeconds(100),
            new BigDecimal("8.33"),
            Ids.newId(),
            false,
            null,
            alreadyRated ? 5 : null,
            alreadyRated ? "ok" : null,
            alreadyRated ? NOW.minusSeconds(50) : null,
            null,
            NOW.minusSeconds(700),
            NOW.minusSeconds(100),
            null));
    return id;
  }

  private Consult queueConsult(String status, Instant createdAt) {
    return new Consult(
        Ids.newId(),
        CUSTOMER,
        null,
        "P",
        "+91",
        Consult.SLOT_NOW,
        null,
        List.of(),
        List.of(),
        null,
        false,
        "GENERAL",
        status,
        Consult.STATUS_IN_CALL.equals(status) ? createdAt : null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        createdAt,
        createdAt,
        null);
  }

  private TeleconsultDoctor doctor(BigDecimal avg, int total, int today) {
    return new TeleconsultDoctor(
        Ids.newId(),
        "Dr A",
        "MBBS",
        "KA" + Math.abs(Ids.newId().hashCode()),
        "GP",
        List.of("English"),
        5,
        "https://cdn/x.jpg",
        "bio",
        "cipher",
        true,
        avg,
        total,
        today,
        null,
        NOW,
        NOW,
        null);
  }

  private static final class RecordingNotifications implements NotificationDispatchPort {
    final List<String> statusUpdates = new CopyOnWriteArrayList<>();

    @Override
    public void notifyConsultAutoCancelled(UUID customerId, UUID consultId) {}

    @Override
    public void notifyConsultStatusUpdated(UUID customerId, UUID consultId, String status) {
      statusUpdates.add(status);
    }
  }

  private static final class FakeDoctorStore implements TeleconsultDoctorStore {
    final Map<UUID, TeleconsultDoctor> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(TeleconsultDoctor doctor) {
      byId.put(doctor.id(), doctor);
    }

    @Override
    public void update(TeleconsultDoctor doctor) {
      byId.put(doctor.id(), doctor);
    }

    @Override
    public Optional<TeleconsultDoctor> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<TeleconsultDoctor> findByRegistrationNo(String registrationNo) {
      return Optional.empty();
    }

    @Override
    public TeleconsultDoctorStore.Page list(TeleconsultDoctorStore.ListFilter filter) {
      return new TeleconsultDoctorStore.Page(List.of(), 0);
    }

    @Override
    public int resetConsultsToday() {
      return 0;
    }

    @Override
    public List<TeleconsultDoctor> listAvailable() {
      return List.of();
    }
  }

  private static final class FakeConsultStore implements ConsultStore {
    final Map<UUID, Consult> byId = new ConcurrentHashMap<>();
    final List<ConsultStatusEvent> events = new CopyOnWriteArrayList<>();
    AdminDayStats dayStats = new AdminDayStats(0, 0, 0, 0, null, null, 0);
    List<AdminListItem> adminItems = List.of();

    @Override
    public void insert(Consult consult) {
      byId.put(consult.id(), consult);
    }

    @Override
    public void update(Consult consult) {
      byId.put(consult.id(), consult);
    }

    @Override
    public void insertStatusEvent(ConsultStatusEvent event) {
      events.add(event);
    }

    @Override
    public Optional<Consult> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Consult> findByIdForCustomer(UUID id, UUID customerId) {
      return findById(id).filter(c -> c.customerId().equals(customerId));
    }

    @Override
    public long countActiveByCustomer(UUID customerId) {
      return 0;
    }

    @Override
    public boolean hasActiveCartModeConsult(UUID cartId) {
      return false;
    }

    @Override
    public Page list(ListFilter filter) {
      return new Page(List.of(), 0);
    }

    @Override
    public int countQueuedNowAheadOrEqual(Instant createdAt) {
      return 0;
    }

    @Override
    public Optional<Integer> rollingAvgCallDurationMinutes() {
      return Optional.empty();
    }

    @Override
    public List<Consult> findDueForAutoCancel(Instant deadlineBefore) {
      return List.of();
    }

    @Override
    public List<QueueItem> listActiveQueue() {
      return byId.values().stream()
          .filter(Consult::isActive)
          .sorted(
              Comparator.comparingInt(
                      (Consult c) ->
                          switch (c.status()) {
                            case "IN_CALL" -> 1;
                            case "CALLING" -> 2;
                            case "DOCTOR_REVIEWING" -> 3;
                            case "REQUESTED" -> 4;
                            default -> 5;
                          })
                  .thenComparing(Consult::createdAt))
          .map(
              c ->
                  new QueueItem(
                      c.id(),
                      c.status(),
                      c.patientName(),
                      c.patientPhone(),
                      null,
                      c.medicinesNeedingRx().stream().map(Consult.MedicineNeed::name).toList(),
                      c.callStartedAt(),
                      c.createdAt(),
                      c.cartMode()))
          .toList();
    }

    @Override
    public Map<String, Long> countActiveByStatus() {
      Map<String, Long> m = new ConcurrentHashMap<>();
      for (Consult c : byId.values()) {
        if (c.isActive()) {
          m.merge(c.status(), 1L, Long::sum);
        }
      }
      return m;
    }

    @Override
    public AdminPage adminList(AdminListFilter filter) {
      return new AdminPage(adminItems, adminItems.size());
    }

    @Override
    public AdminDayStats adminDayStats(Instant rangeStart, Instant rangeEnd) {
      return dayStats;
    }

    @Override
    public long countRatingsByDoctor(UUID doctorId) {
      return byId.values().stream()
          .filter(c -> doctorId.equals(c.doctorId()) && c.rating() != null)
          .count();
    }

    @Override
    public DoctorPeriodStats doctorPeriodStats(
        UUID doctorId, Instant rangeStart, Instant rangeEnd) {
      return new DoctorPeriodStats(0, null, 0, 0, null, List.of());
    }
  }
}
