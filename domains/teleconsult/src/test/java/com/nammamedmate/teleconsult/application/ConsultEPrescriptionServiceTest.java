package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.application.ConsultEPrescriptionService.IssueRequest;
import com.nammamedmate.teleconsult.application.port.out.CartLinkPort;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.Issued;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.MedicineLine;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsultEPrescriptionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:40:00Z");
  private static final UUID CUSTOMER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID DOCTOR_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID CART = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

  private ConsultStore consultStore;
  private TeleconsultDoctorStore doctorStore;
  private EPrescriptionWritePort writePort;
  private CartLinkPort cartLink;
  private NotificationDispatchPort notifications;
  private RateLimiter rateLimiter;
  private ConsultEPrescriptionService service;

  @BeforeEach
  void setUp() {
    consultStore = mock(ConsultStore.class);
    doctorStore = mock(TeleconsultDoctorStore.class);
    writePort = mock(EPrescriptionWritePort.class);
    cartLink = mock(CartLinkPort.class);
    notifications = mock(NotificationDispatchPort.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), any(Integer.class), any(Integer.class)))
        .thenReturn(true);
    service =
        new ConsultEPrescriptionService(
            consultStore,
            doctorStore,
            writePort,
            cartLink,
            notifications,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void issue_cartMode_autoLinksAndCompletesInCall() {
    Consult consult = consult(Consult.STATUS_IN_CALL, true, null);
    when(consultStore.findById(consult.id())).thenReturn(Optional.of(consult));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    UUID rxId = Ids.newId();
    when(writePort.create(any()))
        .thenReturn(
            new Issued(
                rxId,
                "RX-20260724-NMM-000451",
                "hash",
                NOW.plusSeconds(7776000),
                NOW,
                List.of(
                    new MedicineLine(
                        "Metformin 500mg", "500mg", "1-0-1", 60, "tablets", 30, "food"))));

    Map<String, Object> data =
        service.issue(
            admin(),
            consult.id(),
            new IssueRequest(
                List.of(
                    Map.of(
                        "name",
                        "Metformin 500mg",
                        "dosage",
                        "500mg",
                        "frequency",
                        "1-0-1",
                        "quantity",
                        60,
                        "unit",
                        "tablets",
                        "duration_days",
                        30,
                        "notes",
                        "food")),
                false,
                null,
                "clinical"));

    assertThat(data.get("cart_linked")).isEqualTo(true);
    assertThat(data.get("cart_id")).isEqualTo(CART);
    assertThat(data.get("prescription_id")).isEqualTo(rxId);
    verify(cartLink).attachPrescription(CUSTOMER, CART, rxId);
    ArgumentCaptor<Consult> captor = ArgumentCaptor.forClass(Consult.class);
    verify(consultStore).update(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(Consult.STATUS_COMPLETED);
    assertThat(captor.getValue().ePrescriptionId()).isEqualTo(rxId);
    verify(notifications).notifyConsultStatusUpdated(CUSTOMER, consult.id(), "COMPLETED");
    verify(doctorStore).update(any(TeleconsultDoctor.class));
  }

  @Test
  void issue_completedConsult_noStatusTransition() {
    Consult consult = consult(Consult.STATUS_COMPLETED, false, null);
    when(consultStore.findById(consult.id())).thenReturn(Optional.of(consult));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    when(writePort.create(any()))
        .thenReturn(new Issued(Ids.newId(), "RX-1", "h", NOW.plusSeconds(1), NOW, List.of()));

    service.issue(admin(), consult.id(), new IssueRequest(null, true, "Rest and hydrate", null));
    verify(notifications, never()).notifyConsultStatusUpdated(any(), any(), anyString());
    verify(cartLink, never()).attachPrescription(any(), any(), any());
  }

  @Test
  void issue_errors() {
    assertThatThrownBy(() -> service.issue(null, Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(consultStore.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.issue(admin(), Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_FOUND");

    Consult unassignedBase = consult(Consult.STATUS_IN_CALL, false, null);
    final Consult unassigned =
        new Consult(
            unassignedBase.id(),
            CUSTOMER,
            null,
            "Pat",
            "+9199",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            false,
            Consult.REASON_GENERAL,
            Consult.STATUS_IN_CALL,
            NOW,
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
            null);
    when(consultStore.findById(unassigned.id())).thenReturn(Optional.of(unassigned));
    assertThatThrownBy(() -> service.issue(admin(), unassigned.id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_ASSIGNED");

    Consult already = consult(Consult.STATUS_IN_CALL, false, Ids.newId());
    when(consultStore.findById(already.id())).thenReturn(Optional.of(already));
    assertThatThrownBy(() -> service.issue(admin(), already.id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_ALREADY_HAS_EPRESCRIPTION");

    Consult wrongStatus = consult(Consult.STATUS_CALLING, false, null);
    when(consultStore.findById(wrongStatus.id())).thenReturn(Optional.of(wrongStatus));
    assertThatThrownBy(() -> service.issue(admin(), wrongStatus.id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_COMPLETED");

    Consult ok = consult(Consult.STATUS_IN_CALL, false, null);
    when(consultStore.findById(ok.id())).thenReturn(Optional.of(ok));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(null, true, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADVICE_TEXT_REQUIRED");
    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(List.of(), false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINES_REQUIRED");
  }

  @Test
  void issue_validationBranches() {
    Consult ok = consult(Consult.STATUS_IN_CALL, false, null);
    when(consultStore.findById(ok.id())).thenReturn(Optional.of(ok));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));

    assertThatThrownBy(
            () ->
                service.issue(
                    admin(),
                    ok.id(),
                    new IssueRequest(List.of(Map.of("name", "x")), false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.issue(
                    admin(),
                    ok.id(),
                    new IssueRequest(
                        List.of(
                            Map.of(
                                "name",
                                "x",
                                "dosage",
                                "1",
                                "frequency",
                                "1",
                                "quantity",
                                0,
                                "unit",
                                "tablets")),
                        false,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.issue(
                    admin(),
                    ok.id(),
                    new IssueRequest(
                        List.of(
                            Map.of(
                                "name",
                                "x",
                                "dosage",
                                "1",
                                "frequency",
                                "1",
                                "quantity",
                                1,
                                "unit",
                                "pills")),
                        false,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    String longAdvice = "a".repeat(1001);
    assertThatThrownBy(
            () ->
                service.issue(
                    admin(), ok.id(), new IssueRequest(null, true, longAdvice, "  clinical  ")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(rateLimiter.tryAcquire(anyString(), eq(10), eq(60))).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(anyString(), eq(10), eq(60))).thenReturn(3);
    assertThatThrownBy(() -> service.issue(admin(), ok.id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void issue_doctorMissing_andMedicineParseExtras() {
    Consult ok = consult(Consult.STATUS_IN_CALL, false, null);
    when(consultStore.findById(ok.id())).thenReturn(Optional.of(ok));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(null, true, "advice", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONSULT_NOT_ASSIGNED");

    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    when(writePort.create(any()))
        .thenReturn(new Issued(Ids.newId(), "RX", "h", NOW.plusSeconds(1), NOW, List.of()));
    // callStarted null on IN_CALL → duration zero
    Consult noStart =
        new Consult(
            ok.id(),
            CUSTOMER,
            DOCTOR_ID,
            "Pat",
            "+9199",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            false,
            Consult.REASON_GENERAL,
            Consult.STATUS_IN_CALL,
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
            null);
    when(consultStore.findById(noStart.id())).thenReturn(Optional.of(noStart));
    service.issue(admin(), noStart.id(), new IssueRequest(null, true, "ok", "notes"));

    // quantity as string, blank notes
    Consult again = consult(Consult.STATUS_COMPLETED, false, null);
    when(consultStore.findById(again.id())).thenReturn(Optional.of(again));
    Map<String, Object> med = new java.util.LinkedHashMap<>();
    med.put("name", "X");
    med.put("dosage", "1");
    med.put("frequency", "1");
    med.put("quantity", "2");
    med.put("unit", "ml");
    med.put("duration_days", "3");
    med.put("notes", "  ");
    service.issue(admin(), again.id(), new IssueRequest(List.of(med), false, null, null));
  }

  @Test
  void issue_coverageBranches() {
    // cartMode true but cartId null → skip auto-link
    Consult cartModeNoId =
        new Consult(
            Ids.newId(),
            CUSTOMER,
            DOCTOR_ID,
            "Pat",
            "+9199",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            true,
            Consult.REASON_RX_NEEDED,
            Consult.STATUS_COMPLETED,
            NOW,
            NOW,
            BigDecimal.ONE,
            null,
            false,
            "existing notes",
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            null);
    when(consultStore.findById(cartModeNoId.id())).thenReturn(Optional.of(cartModeNoId));
    when(doctorStore.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    when(writePort.create(any()))
        .thenReturn(new Issued(Ids.newId(), "RX", "h", NOW.plusSeconds(1), NOW, null));
    Map<String, Object> data =
        service.issue(
            admin(),
            cartModeNoId.id(),
            new IssueRequest(
                List.of(
                    Map.of(
                        "name",
                        "X",
                        "dosage",
                        "1",
                        "frequency",
                        "1",
                        "quantity",
                        1,
                        "unit",
                        "tablets",
                        "notes",
                        "keep")),
                false,
                "optional advice",
                null));
    assertThat(data.get("cart_linked")).isEqualTo(false);
    verify(cartLink, never()).attachPrescription(any(), any(), any());

    // null medicines list, null medicine row, bad int, blank name, customer forbidden
    Consult ok = consult(Consult.STATUS_COMPLETED, false, null);
    when(consultStore.findById(ok.id())).thenReturn(Optional.of(ok));
    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(null, false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINES_REQUIRED");

    java.util.List<Map<String, Object>> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(withNull, false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> badQty = new java.util.LinkedHashMap<>();
    badQty.put("name", "X");
    badQty.put("dosage", "1");
    badQty.put("frequency", "1");
    badQty.put("quantity", "nope");
    badQty.put("unit", "ml");
    assertThatThrownBy(
            () ->
                service.issue(
                    admin(), ok.id(), new IssueRequest(List.of(badQty), false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> nullQty = new java.util.LinkedHashMap<>();
    nullQty.put("name", "X");
    nullQty.put("dosage", "1");
    nullQty.put("frequency", "1");
    nullQty.put("quantity", null);
    nullQty.put("unit", "ml");
    assertThatThrownBy(
            () ->
                service.issue(
                    admin(), ok.id(), new IssueRequest(List.of(nullQty), false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.issue(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    ok.id(),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    // CreateRequest/Issued null medicines compact ctor via write port return already covered;
    // also exercise CreateRequest null medicines through stub in config is enough for port records
    new EPrescriptionWritePort.CreateRequest(
        Ids.newId(),
        CUSTOMER,
        ok.id(),
        DOCTOR_ID,
        "Dr",
        "MBBS",
        "R",
        "GP",
        "Pat",
        null,
        true,
        "a",
        null,
        NOW);

    when(writePort.create(any()))
        .thenReturn(new Issued(Ids.newId(), "RX2", "h", NOW.plusSeconds(1), NOW, List.of()));
    Map<String, Object> medBlankNotes = new java.util.LinkedHashMap<>();
    medBlankNotes.put("name", "Y");
    medBlankNotes.put("dosage", "1");
    medBlankNotes.put("frequency", "1");
    medBlankNotes.put("quantity", 1);
    medBlankNotes.put("unit", "tablets");
    medBlankNotes.put("duration_days", 5);
    medBlankNotes.put("notes", "   ");
    service.issue(admin(), ok.id(), new IssueRequest(List.of(medBlankNotes), false, "   ", "   "));

    assertThatThrownBy(
            () -> service.issue(admin(), ok.id(), new IssueRequest(null, true, "   ", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADVICE_TEXT_REQUIRED");

    Map<String, Object> blankName = new java.util.LinkedHashMap<>();
    blankName.put("name", "  ");
    blankName.put("dosage", "1");
    blankName.put("frequency", "1");
    blankName.put("quantity", 1);
    blankName.put("unit", "ml");
    assertThatThrownBy(
            () ->
                service.issue(
                    admin(), ok.id(), new IssueRequest(List.of(blankName), false, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private Consult consult(String status, boolean cartMode, UUID eRx) {
    return new Consult(
        Ids.newId(),
        CUSTOMER,
        DOCTOR_ID,
        "Pat",
        "+919999900001",
        Consult.SLOT_NOW,
        null,
        List.of(),
        List.of(),
        cartMode ? CART : null,
        cartMode,
        Consult.REASON_RX_NEEDED,
        status,
        NOW.minusSeconds(600),
        Consult.STATUS_COMPLETED.equals(status) ? NOW : null,
        Consult.STATUS_COMPLETED.equals(status) ? BigDecimal.TEN : null,
        eRx,
        false,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW,
        null);
  }

  private TeleconsultDoctor doctor() {
    return new TeleconsultDoctor(
        DOCTOR_ID,
        "Dr. Anil Mehta",
        "MBBS MD",
        "DL98765",
        "General Medicine",
        List.of("en"),
        10,
        "https://a",
        "bio",
        "cipher",
        true,
        BigDecimal.ZERO,
        5,
        1,
        NOW,
        NOW,
        NOW,
        null);
  }

  private static MedmatePrincipal admin() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }
}
