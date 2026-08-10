package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Branch-fill for ConsultService JaCoCo 100%. */
class ConsultServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUSTOMER = UUID.fromString("c2000001-0000-4000-8000-0000000000c2");
  private static final MedmatePrincipal CUSTOMER_P =
      new MedmatePrincipal(CUSTOMER, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  private ConsultStore consultStore;
  private TeleconsultDoctorStore doctorStore;
  private RateLimiter rateLimiter;
  private ConsultService service;

  @BeforeEach
  void setUp() {
    consultStore = mock(ConsultStore.class);
    doctorStore = mock(TeleconsultDoctorStore.class);
    CartPort cartPort = mock(CartPort.class);
    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(doctorStore.listAvailable()).thenReturn(List.of());
    when(consultStore.countActiveByCustomer(CUSTOMER)).thenReturn(0L);
    when(consultStore.rollingAvgCallDurationMinutes()).thenReturn(Optional.empty());
    when(consultStore.countQueuedNowAheadOrEqual(any())).thenReturn(1);
    service =
        new ConsultService(
            consultStore,
            doctorStore,
            cartPort,
            notifications,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listResultNullDataAndNonCustomerAndBlankInputs() {
    assertThat(new ConsultService.ListResult(null, PaginationMeta.of(1, 20, 0)).data()).isEmpty();

    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(admin, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(
            () -> service.request(CUSTOMER_P, null, "p", "NOW", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.request(CUSTOMER_P, "n", null, "NOW", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.request(CUSTOMER_P, "n", "p", "NOW", null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.request(CUSTOMER_P, "n", "p", "NOW", null, null, null, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.request(CUSTOMER_P, "n", "p", null, null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.request(CUSTOMER_P, "n", "p", "  ", null, null, null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void medicinesAndSymptomsEdgeCases() {
    List<Map<String, Object>> meds = new ArrayList<>();
    meds.add(null);
    Map<String, Object> ok = new HashMap<>();
    ok.put("name", "A");
    ok.put("reason", "NEW_SYMPTOMS");
    meds.add(ok);

    List<String> symptoms = new ArrayList<>();
    symptoms.add(" ");
    symptoms.add(null);
    symptoms.add("ok");
    service.request(CUSTOMER_P, "n", "p", "NOW", symptoms, List.of(), null, "GENERAL");
    service.request(CUSTOMER_P, "n", "p", "NOW", List.of(), meds, null, "GENERAL");

    Map<String, Object> nullName = new HashMap<>();
    nullName.put("name", null);
    nullName.put("reason", "REFILL");
    assertThatThrownBy(
            () ->
                service.request(
                    CUSTOMER_P, "n", "p", "NOW", null, List.of(nullName), null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> nullReason = new HashMap<>();
    nullReason.put("name", "X");
    nullReason.put("reason", null);
    assertThatThrownBy(
            () ->
                service.request(
                    CUSTOMER_P, "n", "p", "NOW", null, List.of(nullReason), null, "GENERAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void cancelWithNullReasonKeepsExistingAndListBlankStatus() {
    UUID id = Ids.newId();
    Consult existing =
        new Consult(
            id,
            CUSTOMER,
            null,
            "Ravi",
            "+91",
            Consult.SLOT_NOW,
            null,
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
            "PRIOR",
            NOW,
            NOW,
            null);
    when(consultStore.findByIdForCustomer(id, CUSTOMER)).thenReturn(Optional.of(existing));
    Map<String, Object> cancelled = service.cancel(CUSTOMER_P, id, null);
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");

    when(consultStore.list(any())).thenReturn(new ConsultStore.Page(List.of(), 0));
    assertThat(service.list(CUSTOMER_P, "  ", 1, 20).data()).isEmpty();
  }
}
