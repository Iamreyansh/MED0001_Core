package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.prescription.application.port.out.CustomerNamePort;
import com.nammamedmate.prescription.application.port.out.OcrJobPort;
import com.nammamedmate.prescription.application.port.out.OcrPort;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionInUsePort;
import com.nammamedmate.prescription.application.port.out.PrescriptionObjectStore;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrescriptionService {

  private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  private static final Set<String> ALLOWED_MIME =
      Set.of("application/pdf", "image/jpeg", "image/png");
  private static final Duration SIGNED_TTL = Duration.ofHours(1);
  private static final Period UPLOADED_TTL = Period.ofMonths(6);

  private final PrescriptionStore store;
  private final PrescriptionObjectStore objectStore;
  private final PresignedUrlService presigner;
  private final OcrPort ocr;
  private final OcrJobPort ocrJobs;
  private final OrderLinkPort orderLink;
  private final PrescriptionInUsePort inUse;
  private final CustomerNamePort customerNames;
  private final DoctorRegistryService doctorRegistry;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PrescriptionService(
      PrescriptionStore store,
      PrescriptionObjectStore objectStore,
      PresignedUrlService presigner,
      OcrPort ocr,
      OcrJobPort ocrJobs,
      OrderLinkPort orderLink,
      PrescriptionInUsePort inUse,
      CustomerNamePort customerNames,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        store,
        objectStore,
        presigner,
        ocr,
        ocrJobs,
        orderLink,
        inUse,
        customerNames,
        null,
        rateLimiter,
        clock);
  }

  @Autowired
  public PrescriptionService(
      PrescriptionStore store,
      PrescriptionObjectStore objectStore,
      PresignedUrlService presigner,
      OcrPort ocr,
      OcrJobPort ocrJobs,
      OrderLinkPort orderLink,
      PrescriptionInUsePort inUse,
      CustomerNamePort customerNames,
      DoctorRegistryService doctorRegistry,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.objectStore = objectStore;
    this.presigner = presigner;
    this.ocr = ocr;
    this.ocrJobs = ocrJobs;
    this.orderLink = orderLink;
    this.inUse = inUse;
    this.customerNames = customerNames;
    this.doctorRegistry = doctorRegistry;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> upload(
      MedmatePrincipal principal,
      byte[] fileBytes,
      String contentType,
      String patientNameRaw,
      String notesRaw) {
    UUID customerId = requireCustomer(principal);
    rateLimit("rx:upload:" + customerId, 10, 60);

    if (fileBytes == null || fileBytes.length == 0) {
      throw new AppException(
          "INVALID_FILE_FORMAT", "Only PDF, JPG, and PNG files are accepted", 422);
    }
    if (fileBytes.length > MAX_FILE_BYTES) {
      throw new AppException("FILE_TOO_LARGE", "File exceeds 10 MB limit", 422);
    }
    String mimeType = resolveMimeType(fileBytes, contentType);

    String notes = normalizeNotes(notesRaw);
    String patientName = normalizePatientName(patientNameRaw, customerId);

    UUID id = Ids.newId();
    String ext = extensionFor(mimeType);
    String s3Key =
        StorageObjectKeys.key(StorageObjectKeys.PRESCRIPTIONS, customerId + "/" + id + "." + ext);

    try {
      objectStore.put(s3Key, fileBytes, mimeType);
    } catch (RuntimeException ex) {
      throw new AppException("UPLOAD_FAILED", "Failed to store prescription file", 500);
    }

    Instant now = clock.instant();
    Instant expiresAt = now.atZone(ZoneOffset.UTC).plus(UPLOADED_TTL).toInstant();
    PrescriptionRecord record =
        new PrescriptionRecord(
            id,
            customerId,
            "UPLOADED",
            "UPLOADED",
            s3Key,
            fileBytes.length,
            mimeType,
            patientName,
            notes,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            expiresAt,
            null,
            now,
            now,
            null);
    store.insert(record);
    ocrJobs.schedule(id, fileBytes, mimeType);
    return toUploadView(record);
  }

  /** Applies OCR results (called by OcrJobPort). Failure leaves fields null. */
  @Transactional
  public void applyOcr(UUID prescriptionId, byte[] fileBytes, String mimeType) {
    try {
      OcrPort.OcrResult result = ocr.extract(fileBytes, mimeType);
      if (result == null) {
        return;
      }
      store.updateOcr(
          prescriptionId,
          result.doctorName(),
          result.prescriptionDate(),
          result.medicines(),
          clock.instant());
      if (doctorRegistry != null) {
        doctorRegistry.upsertFromOcr(
            prescriptionId,
            result.doctorName(),
            result.registrationNo(),
            result.qualification(),
            result.specialty());
      }
    } catch (RuntimeException ignored) {
      // OCR failure must not affect usability
    }
  }

  public record ListResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  public ListResult list(
      MedmatePrincipal principal,
      String status,
      String type,
      Integer pageRaw,
      Integer limitRaw,
      String sort,
      String order) {
    UUID customerId = requireCustomer(principal);
    rateLimit("rx:list:" + customerId, 30, 60);
    int page = pageRaw == null || pageRaw < 1 ? 1 : pageRaw;
    int limit = limitRaw == null ? 20 : Math.min(Math.max(limitRaw, 1), 100);
    String statusFilter = normalizeFilter(status);
    String typeFilter = normalizeFilter(type);
    if (typeFilter != null
        && !"UPLOADED".equals(typeFilter)
        && !"E_PRESCRIPTION".equals(typeFilter)) {
      throw new AppException("VALIDATION_ERROR", "type must be UPLOADED or E_PRESCRIPTION", 400);
    }
    PrescriptionStore.Page result =
        store.listForCustomer(customerId, statusFilter, typeFilter, page, limit, sort, order);
    List<Map<String, Object>> data = new ArrayList<>();
    for (PrescriptionRecord r : result.items()) {
      data.add(toListView(r));
    }
    return new ListResult(data, PaginationMeta.of(page, limit, result.total()));
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    UUID customerId = requireCustomer(principal);
    rateLimit("rx:get:" + customerId, 60, 60);
    PrescriptionRecord record = requireOwned(id, customerId);
    return toDetailView(record);
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id) {
    UUID customerId = requireCustomer(principal);
    rateLimit("rx:delete:" + customerId, 10, 60);
    PrescriptionRecord record = requireOwned(id, customerId);
    if ("E_PRESCRIPTION".equals(record.type())) {
      throw new AppException(
          "CANNOT_DELETE_EPRESCRIPTION",
          "e-Prescriptions cannot be deleted (permanent medical record)",
          403);
    }
    if (inUse.isInUse(id)) {
      throw new AppException(
          "PRESCRIPTION_IN_USE", "Prescription is linked to an active or dispensed order", 409);
    }
    Instant now = clock.instant();
    store.softDelete(id, now, now);
    return Map.of("message", "Prescription deleted successfully");
  }

  @Transactional
  public Map<String, Object> useInCart(MedmatePrincipal principal, UUID id, UUID cartId) {
    UUID customerId = requireCustomer(principal);
    rateLimit("rx:use-cart:" + customerId, 20, 60);
    if (cartId == null) {
      throw new AppException("VALIDATION_ERROR", "cart_id is required", 400);
    }
    PrescriptionRecord record = requireOwned(id, customerId);
    Instant now = clock.instant();
    if (record.isExpired(now)) {
      throw new AppException(
          "PRESCRIPTION_EXPIRED", "Prescription has passed its expiry date", 422);
    }
    if ("REJECTED".equals(record.status())) {
      throw new AppException(
          "PRESCRIPTION_REJECTED", "Prescription was rejected by pharmacist", 422);
    }
    orderLink.attachToCart(customerId, cartId, id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cartId);
    data.put("prescription_id", id);
    data.put("prescription_status", record.status());
    data.put("message", "Prescription attached to cart");
    return data;
  }

  @Transactional
  public int expireDue() {
    Instant now = clock.instant();
    return store.markExpiredDue(now, now);
  }

  private PrescriptionRecord requireOwned(UUID id, UUID customerId) {
    return store
        .findByIdForCustomer(id, customerId)
        .orElseThrow(
            () -> new AppException("PRESCRIPTION_NOT_FOUND", "Prescription not found", 404));
  }

  private String normalizePatientName(String raw, UUID customerId) {
    if (raw != null && !raw.isBlank()) {
      String name = raw.trim();
      if (name.length() > 200) {
        throw new AppException("VALIDATION_ERROR", "patient_name max 200 characters", 400);
      }
      return name;
    }
    return customerNames.findName(customerId).orElse(null);
  }

  private static String normalizeNotes(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String notes = raw.trim();
    if (notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 characters", 400);
    }
    return notes;
  }

  private static String normalizeFilter(String raw) {
    if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
      return null;
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  static String resolveMimeType(byte[] fileBytes, String contentType) {
    String sniffed = sniffMime(fileBytes);
    if (sniffed == null) {
      throw new AppException(
          "INVALID_FILE_FORMAT", "Only PDF, JPG, and PNG files are accepted", 422);
    }
    String claimed =
        contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    if ("image/jpg".equals(claimed)) {
      claimed = "image/jpeg";
    }
    if (!claimed.isEmpty() && ALLOWED_MIME.contains(claimed) && !claimed.equals(sniffed)) {
      throw new AppException(
          "INVALID_FILE_FORMAT", "Only PDF, JPG, and PNG files are accepted", 422);
    }
    if (!claimed.isEmpty() && !ALLOWED_MIME.contains(claimed)) {
      throw new AppException(
          "INVALID_FILE_FORMAT", "Only PDF, JPG, and PNG files are accepted", 422);
    }
    return sniffed;
  }

  static String sniffMime(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    if (bytes.length >= 4
        && bytes[0] == 0x25
        && bytes[1] == 0x50
        && bytes[2] == 0x44
        && bytes[3] == 0x46) {
      return "application/pdf";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4E
        && bytes[3] == 0x47
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }
    return null;
  }

  private static String extensionFor(String mimeType) {
    if ("application/pdf".equals(mimeType)) {
      return "pdf";
    }
    if ("image/png".equals(mimeType)) {
      return "png";
    }
    return "jpg";
  }

  private String freshFileUrl(String s3Key) {
    String base = presigner.createGetUrl(s3Key, SIGNED_TTL).url();
    String sep = base.contains("?") ? "&" : "?";
    // Ensure each read yields a distinct URL (local stub is otherwise stable).
    return base + sep + "n=" + Ids.newId();
  }

  private Map<String, Object> toUploadView(PrescriptionRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", r.id());
    data.put("type", r.type());
    data.put("status", r.status());
    data.put("file_url", freshFileUrl(r.s3Key()));
    data.put("patient_name", r.patientName());
    data.put("notes", r.notes());
    data.put("doctor_name", r.doctorName());
    data.put("prescription_date", r.prescriptionDate());
    data.put("medicines_extracted", null);
    data.put("source", r.source());
    data.put("expires_at", r.expiresAt());
    data.put("uploaded_at", r.createdAt());
    data.put("created_at", r.createdAt());
    return data;
  }

  private Map<String, Object> toListView(PrescriptionRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", r.id());
    data.put("type", r.type());
    data.put("status", r.status());
    data.put("file_url", freshFileUrl(r.s3Key()));
    data.put("patient_name", r.patientName());
    data.put("doctor_name", r.doctorName());
    data.put("prescription_date", r.prescriptionDate());
    data.put("source", r.source());
    data.put("expires_at", r.expiresAt());
    data.put("associated_order_id", r.associatedOrderId());
    data.put("created_at", r.createdAt());
    return data;
  }

  private Map<String, Object> toDetailView(PrescriptionRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", r.id());
    data.put("type", r.type());
    data.put("status", r.status());
    data.put("file_url", freshFileUrl(r.s3Key()));
    data.put("patient_name", r.patientName());
    data.put("notes", r.notes());
    data.put("doctor_name", r.doctorName());
    data.put("prescription_date", r.prescriptionDate());
    data.put("source", r.source());
    data.put("medicines_extracted", medicinesToMaps(r.medicinesExtracted()));
    data.put("associated_order_id", r.associatedOrderId());
    data.put(
        "associated_orders",
        r.associatedOrderId() == null ? List.of() : List.of(r.associatedOrderId()));
    data.put("expires_at", r.expiresAt());
    data.put("uploaded_at", r.createdAt());
    data.put("created_at", r.createdAt());
    data.put("updated_at", r.updatedAt());
    return data;
  }

  private static List<Map<String, Object>> medicinesToMaps(List<MedicineExtracted> meds) {
    if (meds == null) {
      return null;
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (MedicineExtracted m : meds) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", m.name());
      row.put("quantity", m.quantity());
      row.put("dosage", m.dosage());
      row.put("schedule", m.schedule());
      out.add(row);
    }
    return out;
  }

  private static UUID requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
