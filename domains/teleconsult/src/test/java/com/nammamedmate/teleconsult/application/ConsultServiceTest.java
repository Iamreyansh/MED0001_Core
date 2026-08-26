package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminDayStats;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminPage;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.DoctorPeriodStats;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListItem;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.Page;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.QueueItem;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsultServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUSTOMER = UUID.fromString("c1000001-0000-4000-8000-0000000000c1");
  private static final MedmatePrincipal CUSTOMER_P =
      new MedmatePrincipal(CUSTOMER, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  private FakeConsultStore consultStore;
  private FakeDoctorStore doctorStore;
  private CartPort cartPort;
  private RecordingNotifications notifications;
  private ConsultService service;

  @BeforeEach
  void setUp() {
    consultStore = new FakeConsultStore();
    doctorStore = new FakeDoctorStore();
    cartPort = mock(CartPort.class);
    notifications = new RecordingNotifications();
    service =
        new ConsultService(
            consultStore,
            doctorStore,
            cartPort,
            notifications,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_nowAssignsAvailableDoctor() {
    doctorStore.insert(availableDoctor("Dr A", null));
    Map<String, Object> data =
        service.request(
            CUSTOMER_P, "Ravi", "+91-9", "NOW", List.of("fatigue"), null, null, "GENERAL");
    assertThat(data.get("status")).isEqualTo("DOCTOR_REVIEWING");
    assertThat(data.get("estimated_call_in_minutes")).isEqualTo(3);
    @SuppressWarnings("unchecked")
    Map<String, Object> doctor = (Map<String, Object>) data.get("doctor");
    assertThat(doctor.get("name")).isEqualTo("Dr A");
    assertThat(doctorStore.byId.values().iterator().next().lastAssignedAt()).isEqualTo(NOW);
  }

  @Test
  void ac002_nowQueuesWhenNoDoctors() {
    Map<String, Object> data =
        service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL");
    assertThat(data.get("status")).isEqualTo("REQUESTED");
    assertThat(data.get("doctor")).isNull();
    assertThat(data.get("queue_position")).isEqualTo(1);
    assertThat(data.get("estimated_wait_minutes")).isEqualTo(7);
  }

  @Test
  void ac003_maxActiveConsults() {
    for (int i = 0; i < 3; i++) {
      service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL");
    }
    assertThatThrownBy(
            () -> service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAX_ACTIVE_CONSULTS_REACHED");
  }

  @Test
  void ac004_cannotCancelInCall() {
    UUID id = seedConsult(Consult.STATUS_IN_CALL, null, false);
    assertThatThrownBy(() -> service.cancel(CUSTOMER_P, id, "x"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_CANNOT_CANCEL");
  }

  @Test
  void ac006_autoCancelOverdueScheduled() {
    Instant scheduled = NOW.minusSeconds(31 * 60);
    UUID id =
        insert(
            new Consult(
                Ids.newId(),
                CUSTOMER,
                null,
                "Ravi",
                "+91-9",
                Consult.SLOT_SCHEDULED,
                scheduled,
                List.of(),
                List.of(),
                null,
                false,
                "GENERAL",
                Consult.STATUS_REQUESTED,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600),
                null));
    assertThat(service.autoCancelOverdue()).isEqualTo(1);
    assertThat(consultStore.byId.get(id).status()).isEqualTo("CANCELLED");
    assertThat(consultStore.byId.get(id).autoCancelledReason())
        .isEqualTo(ConsultService.AUTO_CANCEL_REASON);
    assertThat(notifications.autoCancelled).containsExactly(id);
  }

  @Test
  void assignDueScheduledAssignsLruDoctor() {
    doctorStore.insert(availableDoctor("Dr A", null));
    UUID id =
        insert(
            new Consult(
                Ids.newId(),
                CUSTOMER,
                null,
                "Ravi",
                "+91-9",
                Consult.SLOT_SCHEDULED,
                NOW.minusSeconds(60),
                List.of(),
                List.of(),
                null,
                false,
                "GENERAL",
                Consult.STATUS_REQUESTED,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600),
                null));
    assertThat(service.assignDueScheduled()).isEqualTo(1);
    assertThat(consultStore.byId.get(id).doctorId()).isNotNull();
    assertThat(consultStore.byId.get(id).status()).isEqualTo(Consult.STATUS_DOCTOR_REVIEWING);
    assertThat(service.assignDueScheduled()).isZero();
  }

  @Test
  void assignDueScheduledSkipsWhenNoDoctorAvailable() {
    UUID id =
        insert(
            new Consult(
                Ids.newId(),
                CUSTOMER,
                null,
                "Ravi",
                "+91-9",
                Consult.SLOT_SCHEDULED,
                NOW.minusSeconds(60),
                List.of(),
                List.of(),
                null,
                false,
                "GENERAL",
                Consult.STATUS_REQUESTED,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600),
                null));
    assertThat(service.assignDueScheduled()).isZero();
    assertThat(consultStore.byId.get(id).doctorId()).isNull();
    ConsultStore defaults = org.mockito.Mockito.mock(ConsultStore.class);
    org.mockito.Mockito.when(defaults.findDueForScheduledAssign(org.mockito.ArgumentMatchers.any()))
        .thenCallRealMethod();
    assertThat(defaults.findDueForScheduledAssign(NOW)).isEmpty();
  }

  @Test
  void ac007_cartAlreadyHasConsult() {
    UUID cartId = Ids.newId();
    when(cartPort.isActiveCartOwnedBy(cartId, CUSTOMER)).thenReturn(true);
    service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, cartId, "RX_NEEDED");
    assertThatThrownBy(
            () ->
                service.request(
                    CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, cartId, "RX_NEEDED"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_ALREADY_HAS_CONSULT");
  }

  @Test
  void ac008_otherCustomerGetIs404() {
    UUID id = seedConsult(Consult.STATUS_REQUESTED, null, false);
    MedmatePrincipal other =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(other, id))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_FOUND");
  }

  @Test
  void cartNotFoundAndScheduledAndCancelAndList() {
    UUID cartId = Ids.newId();
    when(cartPort.isActiveCartOwnedBy(cartId, CUSTOMER)).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.request(
                    CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, cartId, "RX_NEEDED"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_NOT_FOUND");

    Map<String, Object> scheduled =
        service.request(
            CUSTOMER_P,
            "Ravi",
            "+91-9",
            "2026-08-01T10:00:00Z",
            List.of("a", "b"),
            List.of(Map.of("name", "Metformin", "reason", "REFILL")),
            null,
            "RX_NEEDED");
    assertThat(scheduled.get("status")).isEqualTo("REQUESTED");
    assertThat(scheduled.get("scheduled_at")).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
    assertThat(scheduled.get("doctor")).isNull();

    UUID id = (UUID) scheduled.get("consult_id");
    Map<String, Object> detail = service.get(CUSTOMER_P, id);
    assertThat(detail.get("patient_name")).isEqualTo("Ravi");
    assertThat(detail).doesNotContainKey("patient_phone");

    Map<String, Object> cancelled = service.cancel(CUSTOMER_P, id, "No longer needed");
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");

    ConsultService.ListResult list = service.list(CUSTOMER_P, "CANCELLED", 1, 20);
    assertThat(list.data()).hasSize(1);
    assertThat(list.data().get(0)).doesNotContainKey("patient_phone");
    assertThat(list.meta().hasNext()).isFalse();
  }

  @Test
  void validationBranches() {
    RateLimiter unlimited = mock(RateLimiter.class);
    when(unlimited.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    ConsultService svc =
        new ConsultService(
            consultStore,
            doctorStore,
            cartPort,
            notifications,
            unlimited,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> svc.request(null, "a", "b", "NOW", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.request(CUSTOMER_P, " ", "b", "NOW", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.request(CUSTOMER_P, "a", "b", "NOW", null, null, null, "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> svc.request(CUSTOMER_P, "a", "b", "not-a-slot", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                svc.request(
                    CUSTOMER_P, "a", "b", "2020-01-01T00:00:00Z", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                svc.request(
                    CUSTOMER_P,
                    "a",
                    "b",
                    "NOW",
                    List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"),
                    null,
                    null,
                    "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                svc.request(
                    CUSTOMER_P,
                    "a",
                    "b",
                    "NOW",
                    null,
                    List.of(Map.of("name", "", "reason", "REFILL")),
                    null,
                    "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                svc.request(
                    CUSTOMER_P,
                    "a",
                    "b",
                    "NOW",
                    null,
                    List.of(Map.of("name", "X", "reason", "BAD")),
                    null,
                    "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.list(CUSTOMER_P, "BOGUS", 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void queueUsesRollingAverageAndRateLimit() {
    consultStore.avgMinutes = Optional.of(10);
    service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL");
    Map<String, Object> second =
        service.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL");
    assertThat(second.get("queue_position")).isEqualTo(2);
    assertThat(second.get("estimated_wait_minutes")).isEqualTo(20);

    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(limited.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(5);
    ConsultService rl =
        new ConsultService(
            consultStore,
            doctorStore,
            cartPort,
            notifications,
            limited,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () -> rl.request(CUSTOMER_P, "Ravi", "+91-9", "NOW", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void getWithDoctorAndCancelBlankReason() {
    TeleconsultDoctor d = availableDoctor("Dr B", NOW);
    doctorStore.insert(d);
    UUID id = seedConsult(Consult.STATUS_DOCTOR_REVIEWING, d.id(), false);
    Map<String, Object> detail = service.get(CUSTOMER_P, id);
    @SuppressWarnings("unchecked")
    Map<String, Object> doctor = (Map<String, Object>) detail.get("doctor");
    assertThat(doctor.get("name")).isEqualTo("Dr B");
    Map<String, Object> cancelled = service.cancel(CUSTOMER_P, id, "  ");
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
  }

  @Test
  void listDefaultsAndGetMissingDoctor() {
    UUID orphanDoctor = Ids.newId();
    UUID id = seedConsult(Consult.STATUS_REQUESTED, orphanDoctor, false);
    Map<String, Object> detail = service.get(CUSTOMER_P, id);
    assertThat(detail.get("doctor")).isNull();
    assertThat(service.list(CUSTOMER_P, null, null, null).data()).hasSize(1);
    assertThat(service.list(CUSTOMER_P, "ALL", 0, 0).meta().page()).isEqualTo(1);
  }

  private UUID seedConsult(String status, UUID doctorId, boolean cartMode) {
    return insert(
        new Consult(
            Ids.newId(),
            CUSTOMER,
            doctorId,
            "Ravi",
            "+91-9",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            cartMode ? Ids.newId() : null,
            cartMode,
            "GENERAL",
            status,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            null));
  }

  private UUID insert(Consult c) {
    consultStore.insert(c);
    return c.id();
  }

  private TeleconsultDoctor availableDoctor(String name, Instant lastAssigned) {
    return new TeleconsultDoctor(
        Ids.newId(),
        name,
        "MBBS",
        "KA" + Math.abs(name.hashCode()),
        "GP",
        List.of("English"),
        5,
        "https://cdn/x.jpg",
        "bio",
        "cipher",
        true,
        new BigDecimal("4.50"),
        0,
        0,
        lastAssigned,
        NOW,
        NOW,
        null);
  }

  private static final class RecordingNotifications implements NotificationDispatchPort {
    final List<UUID> autoCancelled = new CopyOnWriteArrayList<>();
    final List<String> statusUpdates = new CopyOnWriteArrayList<>();

    @Override
    public void notifyConsultAutoCancelled(UUID customerId, UUID consultId) {
      autoCancelled.add(consultId);
    }

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
      return byId.values().stream().filter(TeleconsultDoctor::available).toList();
    }
  }

  private static final class FakeConsultStore implements ConsultStore {
    final Map<UUID, Consult> byId = new ConcurrentHashMap<>();
    Optional<Integer> avgMinutes = Optional.empty();

    @Override
    public void insert(Consult consult) {
      byId.put(consult.id(), consult);
    }

    @Override
    public void update(Consult consult) {
      byId.put(consult.id(), consult);
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
      return byId.values().stream()
          .filter(c -> c.customerId().equals(customerId) && c.isActive())
          .count();
    }

    @Override
    public boolean hasActiveCartModeConsult(UUID cartId) {
      return byId.values().stream()
          .anyMatch(c -> cartId.equals(c.cartId()) && c.cartMode() && c.isActive());
    }

    @Override
    public Page list(ListFilter filter) {
      List<Consult> all =
          byId.values().stream()
              .filter(c -> c.customerId().equals(filter.customerId()))
              .filter(
                  c ->
                      "ALL".equalsIgnoreCase(filter.status())
                          || filter.status().equalsIgnoreCase(c.status()))
              .sorted(Comparator.comparing(Consult::createdAt).reversed())
              .toList();
      int page = Math.max(filter.page(), 1);
      int limit = Math.min(Math.max(filter.limit(), 1), 100);
      int from = Math.min((page - 1) * limit, all.size());
      int to = Math.min(from + limit, all.size());
      List<ListItem> items = new ArrayList<>();
      for (Consult c : all.subList(from, to)) {
        items.add(
            new ListItem(
                c.id(),
                c.createdAt(),
                null,
                c.status(),
                c.ePrescriptionId(),
                c.cartId(),
                c.cartMode(),
                c.rating()));
      }
      return new Page(items, all.size());
    }

    @Override
    public int countQueuedNowAheadOrEqual(Instant createdAt) {
      return (int)
          byId.values().stream()
              .filter(
                  c ->
                      Consult.SLOT_NOW.equals(c.slotType())
                          && Consult.STATUS_REQUESTED.equals(c.status())
                          && c.doctorId() == null
                          && !c.createdAt().isAfter(createdAt))
              .count();
    }

    @Override
    public Optional<Integer> rollingAvgCallDurationMinutes() {
      return avgMinutes;
    }

    @Override
    public List<Consult> findDueForAutoCancel(Instant deadlineBefore) {
      return byId.values().stream()
          .filter(
              c ->
                  Consult.SLOT_SCHEDULED.equals(c.slotType())
                      && (Consult.STATUS_REQUESTED.equals(c.status())
                          || Consult.STATUS_DOCTOR_REVIEWING.equals(c.status()))
                      && c.scheduledAt() != null
                      && c.scheduledAt().plusSeconds(30 * 60).isBefore(deadlineBefore))
          .toList();
    }

    @Override
    public List<Consult> findDueForScheduledAssign(Instant now) {
      return byId.values().stream()
          .filter(
              c ->
                  Consult.SLOT_SCHEDULED.equals(c.slotType())
                      && Consult.STATUS_REQUESTED.equals(c.status())
                      && c.doctorId() == null
                      && c.scheduledAt() != null
                      && !c.scheduledAt().isAfter(now))
          .toList();
    }

    @Override
    public void insertStatusEvent(com.nammamedmate.teleconsult.domain.ConsultStatusEvent event) {}

    @Override
    public List<QueueItem> listActiveQueue() {
      return List.of();
    }

    @Override
    public Map<String, Long> countActiveByStatus() {
      return Map.of();
    }

    @Override
    public AdminPage adminList(AdminListFilter filter) {
      return new AdminPage(List.of(), 0);
    }

    @Override
    public AdminDayStats adminDayStats(Instant rangeStart, Instant rangeEnd) {
      return new AdminDayStats(0, 0, 0, 0, null, null, 0);
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
