package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.PresignedUrlService.PresignedUrl;
import com.nammamedmate.prescription.application.EPrescriptionService.CreateCommand;
import com.nammamedmate.prescription.application.EPrescriptionService.Created;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.EPrescriptionStore;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionObjectStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.EPrescriptionRecord;
import com.nammamedmate.prescription.domain.EPrescriptionSignature;
import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EPrescriptionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:40:00Z");
  private static final UUID CUSTOMER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID DOCTOR = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID CONSULT = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

  private EPrescriptionStore store;
  private OrderLinkPort orderLink;
  private PrescriptionObjectStore objectStore;
  private PresignedUrlService presigner;
  private DoctorRegistryService doctorRegistry;
  private DoctorCardPort doctorCards;
  private DoctorStore doctorStore;
  private RateLimiter rateLimiter;
  private EPrescriptionService service;

  @BeforeEach
  void setUp() {
    store = mock(EPrescriptionStore.class);
    orderLink = mock(OrderLinkPort.class);
    objectStore = mock(PrescriptionObjectStore.class);
    presigner = mock(PresignedUrlService.class);
    doctorRegistry = mock(DoctorRegistryService.class);
    doctorCards = mock(DoctorCardPort.class);
    doctorStore = mock(DoctorStore.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), any(Integer.class), any(Integer.class)))
        .thenReturn(true);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new EPrescriptionService(
            store,
            orderLink,
            objectStore,
            presigner,
            doctorRegistry,
            doctorCards,
            doctorStore,
            rateLimiter,
            clock);
  }

  @Test
  void createFromTeleconsult_persistsVerifiedWalletRowAndUpsertsDoctor() {
    when(store.nextRxSequence()).thenReturn(451L);
    UUID id = Ids.newId();
    List<MedicinePrescribed> meds =
        List.of(
            new MedicinePrescribed("Metformin 500mg", "500mg", "1-0-1", 60, "tablets", 30, "food"),
            new MedicinePrescribed("Glipizide 5mg", "5mg", "0-1-0", 30, "tablets", 30, null));
    Created created =
        service.createFromTeleconsult(
            new CreateCommand(
                id,
                CUSTOMER,
                CONSULT,
                DOCTOR,
                "Dr. Anil Mehta",
                "MBBS MD",
                "DL98765",
                "General Medicine",
                "Ravi Kumar",
                meds,
                false,
                null,
                "notes",
                NOW));
    assertThat(created.rxId()).isEqualTo("RX-20260724-NMM-000451");
    assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    assertThat(created.digitalSignatureHash())
        .isEqualTo(EPrescriptionSignature.compute(DOCTOR, "Ravi Kumar", meds, NOW));
    verify(store).insert(any(EPrescriptionRecord.class));
    verify(doctorRegistry)
        .upsertFromTeleconsult(id, "Dr. Anil Mehta", "DL98765", "MBBS MD", "General Medicine");
  }

  @Test
  void createAdviceOnly_emptyMedicines() {
    when(store.nextRxSequence()).thenReturn(1L);
    Created created =
        service.createFromTeleconsult(
            new CreateCommand(
                Ids.newId(),
                CUSTOMER,
                CONSULT,
                DOCTOR,
                "Dr X",
                "MBBS",
                "R1",
                "GP",
                "Pat",
                List.of(new MedicinePrescribed("ignored", "1", "1", 1, "ml", null, null)),
                true,
                "Rest well",
                null,
                null));
    assertThat(created.medicines()).isEmpty();
  }

  @Test
  void get_customerOwn_signatureValid() {
    EPrescriptionRecord record = sampleRecord(false);
    when(store.findByIdForCustomer(record.id(), CUSTOMER)).thenReturn(Optional.of(record));
    when(doctorCards.findForPrescription(any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new DoctorCardPort.DoctorCard("Dr. Anil Mehta", "MBBS MD", "DL98765", true)));
    when(doctorStore.findLink(record.id())).thenReturn(Optional.empty());

    MedmatePrincipal customer = principal(CUSTOMER, AuthRole.CUSTOMER);
    Map<String, Object> data = service.get(customer, record.id());
    assertThat(data.get("signature_valid")).isEqualTo(true);
    assertThat(data.get("seal")).isEqualTo("VERIFIED");
    assertThat(data.get("rx_type")).isEqualTo("E_PRESCRIPTION");
  }

  @Test
  void get_adminUsesFindById_andSpecialtyFromRegistry() {
    EPrescriptionRecord record = sampleRecord(false);
    when(store.findById(record.id())).thenReturn(Optional.of(record));
    when(doctorCards.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    UUID regDoctor = Ids.newId();
    when(doctorStore.findLink(record.id()))
        .thenReturn(Optional.of(new DoctorStore.Link(record.id(), regDoctor, false, false)));
    when(doctorStore.findById(regDoctor))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    regDoctor,
                    "DL98765",
                    "Dr. Anil Mehta",
                    "MBBS MD",
                    "General Medicine",
                    "VERIFIED",
                    "TELECONSULT",
                    1,
                    0,
                    "MANUAL",
                    null,
                    NOW,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));

    Map<String, Object> data =
        service.get(principal(Ids.newId(), AuthRole.ADMIN_SUPER), record.id());
    @SuppressWarnings("unchecked")
    Map<String, Object> doctor = (Map<String, Object>) data.get("doctor");
    assertThat(doctor.get("specialty")).isEqualTo("General Medicine");
    assertThat(doctor.get("name")).isEqualTo(record.doctorName());
  }

  @Test
  void get_notFoundAndForbiddenAndRateLimited() {
    when(store.findByIdForCustomer(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(principal(CUSTOMER, AuthRole.CUSTOMER), Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_NOT_FOUND");
    assertThatThrownBy(
            () -> service.get(principal(Ids.newId(), AuthRole.PHARMACY_OWNER), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.get(null, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(rateLimiter.tryAcquire(anyString(), eq(60), eq(60))).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(anyString(), eq(60), eq(60))).thenReturn(5);
    assertThatThrownBy(
            () -> service.get(principal(CUSTOMER, AuthRole.ADMIN_COMPLIANCE), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void linkToCart_happyAndErrors() {
    EPrescriptionRecord record = sampleRecord(false);
    when(store.findByIdForCustomer(record.id(), CUSTOMER)).thenReturn(Optional.of(record));
    UUID cart = Ids.newId();
    Map<String, Object> data =
        service.linkToCart(principal(CUSTOMER, AuthRole.CUSTOMER), record.id(), cart);
    assertThat(data.get("message")).isEqualTo("e-Prescription linked to cart successfully");
    verify(orderLink).attachToCart(CUSTOMER, cart, record.id());

    assertThatThrownBy(
            () -> service.linkToCart(principal(CUSTOMER, AuthRole.CUSTOMER), record.id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.linkToCart(principal(Ids.newId(), AuthRole.ADMIN_SUPER), record.id(), cart))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(store.findByIdForCustomer(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.linkToCart(principal(CUSTOMER, AuthRole.CUSTOMER), Ids.newId(), cart))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_NOT_FOUND");
  }

  @Test
  void linkToCart_expired() {
    EPrescriptionRecord expired =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-20260101-NMM-000001",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "rest",
            null,
            "deadbeef",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            null,
            null,
            0,
            null,
            NOW.minus(Duration.ofDays(100)),
            NOW.minus(Duration.ofDays(10)),
            NOW.minus(Duration.ofDays(100)),
            NOW.minus(Duration.ofDays(100)),
            null);
    when(store.findByIdForCustomer(expired.id(), CUSTOMER)).thenReturn(Optional.of(expired));
    assertThatThrownBy(
            () ->
                service.linkToCart(
                    principal(CUSTOMER, AuthRole.CUSTOMER), expired.id(), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_EXPIRED");
  }

  @Test
  void download_generatesPdfOnDemand() {
    EPrescriptionRecord record = sampleRecord(false);
    when(store.findByIdForCustomer(record.id(), CUSTOMER)).thenReturn(Optional.of(record));
    when(presigner.createGetUrl(anyString(), any()))
        .thenReturn(
            new PresignedUrl(
                "https://s3.example/eprescriptions/rx.pdf?sig=1",
                "eprescriptions/rx.pdf",
                Duration.ofMinutes(15)));

    String url = service.downloadUrl(principal(CUSTOMER, AuthRole.CUSTOMER), record.id());
    assertThat(url).contains("eprescriptions");
    verify(objectStore).put(anyString(), any(byte[].class), eq("application/pdf"));
    verify(store).updatePdf(eq(record.id()), anyString(), anyLong(), eq(NOW), eq(NOW));
  }

  @Test
  void download_reusesExistingPdf_andAdviceOnlyPdf() {
    EPrescriptionRecord withPdf =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-20260724-NMM-000002",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "Drink water",
            null,
            "abc",
            true,
            "VERIFIED",
            "VERIFIED",
            "eprescriptions/RX-20260724-NMM-000002.pdf",
            "eprescriptions/RX-20260724-NMM-000002.pdf",
            NOW,
            12,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    when(store.findById(withPdf.id())).thenReturn(Optional.of(withPdf));
    when(presigner.createGetUrl(anyString(), any()))
        .thenReturn(new PresignedUrl("https://s3.example/x.pdf", "x.pdf", Duration.ofMinutes(15)));
    assertThat(service.downloadUrl(principal(Ids.newId(), AuthRole.ADMIN_COMPLIANCE), withPdf.id()))
        .contains("x.pdf");
    verify(objectStore, never()).put(anyString(), any(), anyString());

    assertThatThrownBy(
            () -> service.downloadUrl(principal(Ids.newId(), AuthRole.ADMIN_SUPER), withPdf.id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void generatePdf_coversLongTextBranch() {
    String longAdvice = "x".repeat(9000);
    EPrescriptionRecord record =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-20260724-NMM-000099",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            null,
            null,
            List.of(new MedicinePrescribed("M", "1", "1", 1, "ml", null, null)),
            false,
            longAdvice,
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            null,
            null,
            0,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    byte[] pdf = EPrescriptionService.generatePdf(record);
    assertThat(pdf).startsWith("%PDF".getBytes());
    assertThat(EPrescriptionService.minimalPdf(null)).startsWith("%PDF".getBytes());
    assertThat(EPrescriptionService.minimalPdf("y".repeat(9000))).startsWith("%PDF".getBytes());

    EPrescriptionRecord advice =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-A",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "Rest",
            null,
            "h",
            true,
            null,
            "VERIFIED",
            "k",
            "  ",
            null,
            0,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    assertThat(EPrescriptionService.generatePdf(advice)).startsWith("%PDF".getBytes());
    when(store.findByIdForCustomer(advice.id(), CUSTOMER)).thenReturn(Optional.of(advice));
    when(doctorCards.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(doctorStore.findLink(advice.id())).thenReturn(Optional.empty());
    assertThat(service.get(principal(CUSTOMER, AuthRole.CUSTOMER), advice.id()).get("seal"))
        .isEqualTo("VERIFIED");
    when(presigner.createGetUrl(anyString(), any()))
        .thenReturn(new PresignedUrl("https://s3.example/a.pdf", "a.pdf", Duration.ofMinutes(15)));
    service.downloadUrl(principal(CUSTOMER, AuthRole.CUSTOMER), advice.id());
    verify(objectStore).put(anyString(), any(byte[].class), eq("application/pdf"));
  }

  @Test
  void moreAuthAndExpiryBranches() {
    assertThatThrownBy(() -> service.linkToCart(null, Ids.newId(), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(store.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.downloadUrl(principal(Ids.newId(), AuthRole.ADMIN_COMPLIANCE), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_NOT_FOUND");
    assertThatThrownBy(() -> service.downloadUrl(null, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    EPrescriptionRecord expiredStatus =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-E",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "a",
            null,
            "h",
            true,
            "VERIFIED",
            "EXPIRED",
            "k",
            null,
            null,
            0,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    assertThat(expiredStatus.isExpired(NOW)).isTrue();
    assertThat(
            new EPrescriptionRecord(
                    Ids.newId(),
                    "RX",
                    CUSTOMER,
                    CONSULT,
                    DOCTOR,
                    "Dr",
                    "Pat",
                    null,
                    false,
                    null,
                    null,
                    "h",
                    true,
                    "VERIFIED",
                    "VERIFIED",
                    "k",
                    null,
                    null,
                    0,
                    null,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    null)
                .isExpired(NOW))
        .isFalse();

    when(store.nextRxSequence()).thenReturn(2L);
    service.createFromTeleconsult(
        new CreateCommand(
            Ids.newId(),
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "MBBS",
            "R",
            "GP",
            "Pat",
            null,
            true,
            "advice",
            null,
            NOW));
    new EPrescriptionService.Created(Ids.newId(), "RX", "h", NOW, NOW, null);

    EPrescriptionRecord blankPdfKey =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-B",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "a",
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            "   ",
            NOW,
            1,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    when(store.findByIdForCustomer(blankPdfKey.id(), CUSTOMER))
        .thenReturn(Optional.of(blankPdfKey));
    when(presigner.createGetUrl(anyString(), any()))
        .thenReturn(new PresignedUrl("https://s3.example/b.pdf", "b.pdf", Duration.ofMinutes(15)));
    service.downloadUrl(principal(CUSTOMER, AuthRole.CUSTOMER), blankPdfKey.id());

    EPrescriptionRecord keyNoGenerated =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-C",
            CUSTOMER,
            CONSULT,
            DOCTOR,
            "Dr",
            "Pat",
            List.of(),
            true,
            "a",
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            "eprescriptions/RX-C.pdf",
            null,
            0,
            null,
            NOW,
            NOW.plus(Duration.ofDays(90)),
            NOW,
            NOW,
            null);
    when(store.findByIdForCustomer(keyNoGenerated.id(), CUSTOMER))
        .thenReturn(Optional.of(keyNoGenerated));
    service.downloadUrl(principal(CUSTOMER, AuthRole.CUSTOMER), keyNoGenerated.id());
  }

  private EPrescriptionRecord sampleRecord(boolean adviceOnly) {
    List<MedicinePrescribed> meds =
        adviceOnly
            ? List.of()
            : List.of(
                new MedicinePrescribed(
                    "Metformin 500mg", "500mg", "1-0-1", 60, "tablets", 30, "food"));
    String hash = EPrescriptionSignature.compute(DOCTOR, "Ravi Kumar", meds, NOW);
    return new EPrescriptionRecord(
        Ids.newId(),
        "RX-20260724-NMM-000451",
        CUSTOMER,
        CONSULT,
        DOCTOR,
        "Dr. Anil Mehta",
        "Ravi Kumar",
        meds,
        adviceOnly,
        adviceOnly ? "advice" : null,
        null,
        hash,
        true,
        "VERIFIED",
        "VERIFIED",
        "eprescriptions/RX-20260724-NMM-000451.pdf",
        null,
        null,
        0,
        null,
        NOW,
        NOW.plus(Duration.ofDays(90)),
        NOW,
        NOW,
        null);
  }

  private static MedmatePrincipal principal(UUID id, AuthRole role) {
    return new MedmatePrincipal(id, role, null, TokenScope.FULL, "jti");
  }
}
