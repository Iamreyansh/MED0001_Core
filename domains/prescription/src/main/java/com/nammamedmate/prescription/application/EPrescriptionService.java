package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** e-Prescription create/read/link/download (EPIC-009 STORY-004). */
@Service
public class EPrescriptionService {

  private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(15);
  private static final Duration VALIDITY = Duration.ofDays(90);
  private static final Set<String> READ_ROLES =
      Set.of(
          AuthRole.CUSTOMER.name(), AuthRole.ADMIN_COMPLIANCE.name(), AuthRole.ADMIN_SUPER.name());
  private static final Set<String> DOWNLOAD_ROLES =
      Set.of(AuthRole.CUSTOMER.name(), AuthRole.ADMIN_COMPLIANCE.name());
  private static final DateTimeFormatter RX_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final EPrescriptionStore store;
  private final OrderLinkPort orderLink;
  private final PrescriptionObjectStore objectStore;
  private final PresignedUrlService presigner;
  private final DoctorRegistryService doctorRegistry;
  private final DoctorCardPort doctorCards;
  private final DoctorStore doctorStore;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public EPrescriptionService(
      EPrescriptionStore store,
      OrderLinkPort orderLink,
      PrescriptionObjectStore objectStore,
      PresignedUrlService presigner,
      DoctorRegistryService doctorRegistry,
      DoctorCardPort doctorCards,
      DoctorStore doctorStore,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.orderLink = orderLink;
    this.objectStore = objectStore;
    this.presigner = presigner;
    this.doctorRegistry = doctorRegistry;
    this.doctorCards = doctorCards;
    this.doctorStore = doctorStore;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  /** Called from apps/api bridge (teleconsult issuance). */
  @Transactional
  public Created createFromTeleconsult(CreateCommand cmd) {
    Instant issuedAt = cmd.issuedAt() == null ? clock.instant() : cmd.issuedAt();
    List<MedicinePrescribed> medicines =
        cmd.adviceOnly() ? List.of() : List.copyOf(cmd.medicines());
    String hash =
        EPrescriptionSignature.compute(cmd.doctorId(), cmd.patientName(), medicines, issuedAt);
    long seq = store.nextRxSequence();
    String rxId =
        String.format(Locale.ROOT, "RX-%s-NMM-%06d", RX_DAY.format(issuedAt), seq % 1_000_000L);
    String s3Key = "eprescriptions/" + rxId + ".pdf";
    Instant expiresAt = issuedAt.plus(VALIDITY);
    EPrescriptionRecord record =
        new EPrescriptionRecord(
            cmd.id(),
            rxId,
            cmd.customerId(),
            cmd.teleconsultId(),
            cmd.doctorId(),
            cmd.doctorName(),
            cmd.patientName(),
            medicines,
            cmd.adviceOnly(),
            cmd.adviceText(),
            cmd.clinicalNotes(),
            hash,
            true,
            "VERIFIED",
            "VERIFIED",
            s3Key,
            null,
            null,
            0L,
            null,
            issuedAt,
            expiresAt,
            issuedAt,
            issuedAt,
            null);
    store.insert(record);
    doctorRegistry.upsertFromTeleconsult(
        record.id(), cmd.doctorName(), cmd.registrationNo(), cmd.qualification(), cmd.specialty());
    return new Created(record.id(), rxId, hash, expiresAt, issuedAt, medicines);
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireReadRole(principal);
    rateLimit("eprescription:get:" + principal.subject(), 60, 60);
    EPrescriptionRecord record = requireVisible(principal, id);
    Instant now = clock.instant();
    boolean signatureValid =
        EPrescriptionSignature.verify(
            record.digitalSignatureHash(),
            record.doctorId(),
            record.patientName(),
            record.medicines(),
            record.issuedAt());

    Map<String, Object> doctor = new LinkedHashMap<>();
    doctorCards
        .findForPrescription(
            record.id(), "E_PRESCRIPTION", record.doctorName(), record.teleconsultId())
        .ifPresentOrElse(
            card -> {
              doctor.put("name", card.name());
              doctor.put("qualification", card.qualification());
              doctor.put("registration_no", card.registrationNo());
            },
            () -> {
              doctor.put("name", record.doctorName());
              doctor.put("qualification", null);
              doctor.put("registration_no", null);
            });
    String specialty =
        doctorStore
            .findLink(record.id())
            .flatMap(link -> doctorStore.findById(link.doctorId()))
            .map(DoctorRecord::specialty)
            .orElse(null);
    doctor.put("specialty", specialty);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("prescription_id", record.id());
    data.put("rx_id", record.rxId());
    data.put("rx_type", "E_PRESCRIPTION");
    data.put("is_verified", record.verified());
    data.put("seal", record.seal() == null ? "VERIFIED" : record.seal());
    data.put("doctor", doctor);
    data.put("patient_name", record.patientName());
    data.put("issued_at", record.issuedAt());
    data.put("expires_at", record.expiresAt());
    data.put("is_expired", record.isExpired(now));
    data.put("advice_only", record.adviceOnly());
    data.put("medicines", record.medicines().stream().map(MedicinePrescribed::toApiMap).toList());
    data.put("advice_text", record.adviceText());
    data.put("digital_signature_hash", record.digitalSignatureHash());
    data.put("signature_valid", signatureValid);
    data.put("consult_id", record.teleconsultId());
    data.put("associated_order_id", record.associatedOrderId());
    return data;
  }

  @Transactional
  public Map<String, Object> linkToCart(MedmatePrincipal principal, UUID id, UUID cartId) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
    UUID customerId = principal.subject();
    rateLimit("eprescription:link-cart:" + customerId, 10, 60);
    if (cartId == null) {
      throw new AppException("VALIDATION_ERROR", "cart_id is required", 400);
    }
    EPrescriptionRecord record =
        store
            .findByIdForCustomer(id, customerId)
            .orElseThrow(
                () ->
                    new AppException(
                        "PRESCRIPTION_NOT_FOUND", "e-Rx not found for this customer", 404));
    if (record.isExpired(clock.instant())) {
      throw new AppException("PRESCRIPTION_EXPIRED", "e-Rx has expired", 422);
    }
    orderLink.attachToCart(customerId, cartId, id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("prescription_id", id);
    data.put("cart_id", cartId);
    data.put("cart_prescription_id", id);
    data.put("message", "e-Prescription linked to cart successfully");
    return data;
  }

  /** Ensures PDF exists (on-demand) and returns a 15-minute presigned GET URL. */
  @Transactional
  public String downloadUrl(MedmatePrincipal principal, UUID id) {
    requireDownloadRole(principal);
    rateLimit("eprescription:download:" + principal.subject(), 10, 60);
    EPrescriptionRecord record = requireVisible(principal, id);
    Instant now = clock.instant();
    String pdfKey = record.pdfS3Key();
    if (pdfKey == null || pdfKey.isBlank() || record.pdfGeneratedAt() == null) {
      byte[] pdf = generatePdf(record);
      pdfKey = "eprescriptions/" + record.rxId() + ".pdf";
      objectStore.put(pdfKey, pdf, "application/pdf");
      store.updatePdf(record.id(), pdfKey, pdf.length, now, now);
    }
    return presigner.createGetUrl(pdfKey, DOWNLOAD_TTL).url();
  }

  static byte[] generatePdf(EPrescriptionRecord record) {
    String title = record.adviceOnly() ? "Consultation Receipt" : "e-Prescription";
    StringBuilder body = new StringBuilder();
    body.append(title).append(" ").append(record.rxId()).append(" ");
    body.append("Dr ").append(nullToEmpty(record.doctorName())).append(" ");
    body.append("Patient ").append(nullToEmpty(record.patientName())).append(" ");
    body.append("Issued ").append(record.issuedAt()).append(" ");
    body.append("Expires ").append(record.expiresAt()).append(" ");
    body.append("Seal VERIFIED ");
    if (record.adviceOnly()) {
      body.append("Advice ").append(nullToEmpty(record.adviceText()));
    } else {
      for (MedicinePrescribed m : record.medicines()) {
        body.append(m.name())
            .append(" ")
            .append(m.dosage())
            .append(" ")
            .append(m.frequency())
            .append(" qty ")
            .append(m.quantity())
            .append(" ");
      }
    }
    return minimalPdf(body.toString());
  }

  /** Minimal valid-enough PDF (ponytail until template engine). */
  static byte[] minimalPdf(String text) {
    String safe = text == null ? "" : text.replace('\\', ' ').replace('(', ' ').replace(')', ' ');
    if (safe.length() > 8000) {
      safe = safe.substring(0, 8000);
    }
    String content = "BT /F1 10 Tf 50 750 Td (" + safe.replace("\n", ") Tj T* (") + ") Tj ET";
    StringBuilder pdf = new StringBuilder();
    pdf.append("%PDF-1.4\n");
    pdf.append("1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
    pdf.append("2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
    pdf.append("3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ");
    pdf.append("/Contents 4 0 R /Resources<< /Font<< /F1 5 0 R >> >> >>endobj\n");
    pdf.append("4 0 obj<< /Length ").append(content.length()).append(" >>stream\n");
    pdf.append(content).append("\nendstream\nendobj\n");
    pdf.append("5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n");
    pdf.append("xref\n0 6\n0000000000 65535 f \n");
    pdf.append("trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n0\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private EPrescriptionRecord requireVisible(MedmatePrincipal principal, UUID id) {
    if (principal.role() == AuthRole.CUSTOMER) {
      return store
          .findByIdForCustomer(id, principal.subject())
          .orElseThrow(
              () ->
                  new AppException(
                      "PRESCRIPTION_NOT_FOUND", "e-Rx not found for this customer", 404));
    }
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("PRESCRIPTION_NOT_FOUND", "e-Rx not found", 404));
  }

  private static void requireReadRole(MedmatePrincipal principal) {
    if (principal == null || !READ_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private static void requireDownloadRole(MedmatePrincipal principal) {
    if (principal == null || !DOWNLOAD_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  public record CreateCommand(
      UUID id,
      UUID customerId,
      UUID teleconsultId,
      UUID doctorId,
      String doctorName,
      String qualification,
      String registrationNo,
      String specialty,
      String patientName,
      List<MedicinePrescribed> medicines,
      boolean adviceOnly,
      String adviceText,
      String clinicalNotes,
      Instant issuedAt) {
    public CreateCommand {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }

  public record Created(
      UUID prescriptionId,
      String rxId,
      String digitalSignatureHash,
      Instant expiresAt,
      Instant issuedAt,
      List<MedicinePrescribed> medicines) {
    public Created {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }
}
