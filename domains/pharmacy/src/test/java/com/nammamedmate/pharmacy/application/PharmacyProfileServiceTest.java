package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort.PennyDropResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore.PincodeRecord;
import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore;
import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore.ChangeRequestRecord;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PharmacyProfileServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String GSTIN = "29AABPP1234F1ZZ";
  private static final byte[] AES_KEY = new byte[32];

  private FakeProfileStore profiles;
  private FakeRegistrationStore pharmacies;
  private FakeChangeRequests changes;
  private FakeProfileOtps otps;
  private FakePincodes pincodes;
  private InMemoryOutboxStore outboxStore;
  private AutoKycService autoKyc;
  private PharmacyProfileService service;
  private AdminPharmacyProfileService adminService;
  private FakeAudit audit;

  @BeforeEach
  void setUp() throws Exception {
    profiles = new FakeProfileStore();
    pharmacies = new FakeRegistrationStore();
    changes = new FakeChangeRequests();
    otps = new FakeProfileOtps();
    pincodes = new FakePincodes();
    outboxStore = new InMemoryOutboxStore();
    audit = new FakeAudit();
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    autoKyc = mock(AutoKycService.class);
    AesGcmCipher cipher = new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {1}));
    service =
        new PharmacyProfileService(
            profiles,
            pharmacies,
            changes,
            otps,
            pincodes,
            new PennyDropPort() {
              @Override
              public PennyDropResult initiate(UUID pharmacyId, String ifsc, String last4) {
                return new PennyDropResult("RZP-PENNY-TEST", "PENDING");
              }
            },
            cipher,
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            rateLimiter,
            autoKyc,
            new BCryptPasswordEncoder(),
            () -> MagicProfileOtp.CODE,
            new SecureRandom(new byte[] {2}),
            Clock.fixed(NOW, ZoneOffset.UTC));
    adminService =
        new AdminPharmacyProfileService(
            profiles, audit, service, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    seedActiveProfile();
  }

  @Test
  void businessNameChangeCreatesPendingApproval() {
    Map<String, Object> body = Map.of("business_name", "New Name Pvt Ltd");
    Map<String, Object> data = service.patchProfile(owner(), body);
    assertThat(data.get("pending_approval_fields")).asList().contains("business_name");
    assertThat(changes.records).hasSize(1);
    assertThat(changes.records.get(0).status()).isEqualTo("PENDING_APPROVAL");
    assertThat(profiles.get().businessName()).isEqualTo("Sharma Medical Store");
  }

  @Test
  void phoneChangeSendsOtpWithoutApplying() {
    Map<String, Object> data = service.patchProfile(owner(), Map.of("phone", "+919811100099"));
    assertThat(data.get("pending_verification_fields")).asList().contains("phone");
    assertThat(profiles.get().phone()).isEqualTo("+919876543210");
    assertThat(profiles.get().pendingPhone()).isEqualTo("+919811100099");
    assertThat(otps.latest).isNotNull();
    assertThat(outboxStore.all())
        .anySatisfy(
            e -> {
              assertThat(e.type()).isEqualTo("pharmacy.notification.profile_otp");
              assertThat(e.payloadJson()).contains("pharmacy_id");
              assertThat(e.payloadJson()).contains("otp_id");
              assertThat(e.payloadJson()).contains("\"channel\"");
              assertThat(e.payloadJson()).doesNotContain("\"otp\"");
              assertThat(e.payloadJson()).doesNotContain("\"target\"");
            });
  }

  @Test
  void failedBankAccountCanBeReEntered() {
    profiles.bank =
        new BankAccountRecord(
            Ids.newId(),
            PID,
            "Holder",
            "Bank",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "FAILED",
            "RZP-OLD",
            null,
            NOW,
            NOW);
    Map<String, Object> data =
        service.saveBankAccount(
            owner(),
            Map.of(
                "account_holder",
                "Sharma Medical Store",
                "bank_name",
                "HDFC Bank",
                "account_number",
                "12345678901234",
                "ifsc_code",
                "HDFC0001234",
                "account_type",
                "CURRENT"));
    assertThat(data.get("verification_status")).isEqualTo("PENDING");
    assertThat(profiles.bank.pennyDropReference()).isEqualTo("RZP-PENNY-TEST");
  }

  @Test
  void verifyPhoneAppliesPendingNumber() {
    service.patchProfile(owner(), Map.of("phone", "+919811100099"));
    Map<String, Object> verified = service.verifyContact(owner(), "PHONE", MagicProfileOtp.CODE);
    assertThat(verified.get("verified")).isEqualTo(true);
    assertThat(profiles.get().phone()).isEqualTo("+919811100099");
    assertThat(profiles.get().pendingPhone()).isNull();
  }

  @Test
  void invalidOperatingHoursRejected() {
    List<Map<String, Object>> hours = fullWeek();
    hours.set(
        0,
        Map.of("day_of_week", 0, "open_time", "21:00", "close_time", "09:00", "is_closed", false));
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("operating_hours", hours)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OPERATING_HOURS");
  }

  @Test
  void saveBankAccountInitiatesPennyDrop() {
    Map<String, Object> data =
        service.saveBankAccount(
            owner(),
            Map.of(
                "account_holder",
                "Sharma Medical Store",
                "bank_name",
                "HDFC Bank",
                "account_number",
                "12345678901234",
                "ifsc_code",
                "HDFC0001234",
                "account_type",
                "CURRENT"));
    assertThat(data.get("verification_status")).isEqualTo("PENDING");
    assertThat(data.get("penny_drop_initiated")).isEqualTo(true);
    assertThat(profiles.bank).isNotNull();
    assertThat(profiles.bank.pennyDropReference()).isEqualTo("RZP-PENNY-TEST");
  }

  @Test
  void verifiedBankAccountBlocksNewSave() {
    profiles.bank =
        new BankAccountRecord(
            Ids.newId(),
            PID,
            "Holder",
            "Bank",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "VERIFIED",
            "RZP-1",
            NOW,
            NOW,
            NOW);
    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "X",
                        "bank_name",
                        "Y",
                        "account_number",
                        "123456789",
                        "ifsc_code",
                        "HDFC0001234",
                        "account_type",
                        "CURRENT")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BANK_ACCOUNT_ALREADY_VERIFIED");
  }

  @Test
  void gstinChangeTriggersReVerification() {
    Map<String, Object> data = service.patchTax(owner(), Map.of("gstin", "29AABPP1235F1ZY"));
    assertThat(data.get("re_verification_triggered")).isEqualTo(true);
    verify(autoKyc).triggerGstinReverification(PID);
    assertThat(profiles.get().gstinReverificationPending()).isTrue();
  }

  @Test
  void completenessReflectsMissingFields() {
    Map<String, Object> data = service.getCompleteness(owner());
    assertThat((Integer) data.get("completeness_pct")).isLessThan(100);
    assertThat(data.get("missing_fields")).asList().isNotEmpty();
    assertThat(data.get("completed_fields")).asList().isNotEmpty();
  }

  @Test
  void adminUpdateWritesAuditLog() {
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "jti");
    Map<String, Object> data =
        adminService.patchProfile(admin, PID, Map.of("tagline", "Updated tagline"), "127.0.0.1");
    assertThat(data.get("changed_fields")).asList().contains("tagline");
    assertThat(audit.records).hasSize(1);
    AuditLogRecord log = audit.records.get(0);
    assertThat(log.payload()).containsKeys("changed_fields", "old_values", "new_values");
    assertThat(log.actorRole()).isEqualTo("ADMIN_SUPER");
  }

  @Test
  void nonActivePharmacyRejected() {
    profiles.record = copy(profiles.get(), "SUSPENDED");
    assertThatThrownBy(() -> service.getProfile(owner()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_ACTIVE");
  }

  @Test
  void expireStalePennyDropsMarksFailed() {
    UUID bankId = Ids.newId();
    profiles.bank =
        new BankAccountRecord(
            bankId,
            PID,
            "H",
            "B",
            "enc",
            "4321",
            "HDFC0001234",
            "CURRENT",
            "PENDING",
            "RZP-OLD",
            null,
            NOW.minusSeconds(86401),
            NOW.minusSeconds(86401));
    service.expireStalePennyDrops();
    assertThat(profiles.bank.verificationStatus()).isEqualTo("FAILED");
  }

  private void seedActiveProfile() {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("flat", "12");
    address.put("area", "Koramangala");
    address.put("city", "Bengaluru");
    address.put("state", "Karnataka");
    address.put("pincode", "560034");
    profiles.record =
        new ProfileRecord(
            PID,
            "PHM-0042",
            "Sharma Medical Store",
            "Your neighbourhood pharmacy",
            "https://cdn.example.com/logo.png",
            "+919876543210",
            "owner@nammamedmate.test",
            null,
            null,
            "PHARMACY",
            address,
            "ACTIVE",
            "GROWTH",
            GSTIN,
            "AABPP1234F",
            "KA/DL/2024/12345",
            "11223344556677",
            true,
            false,
            false,
            true,
            false,
            "Dr. Rajesh Sharma",
            NOW,
            NOW);
    for (int d = 0; d < 5; d++) {
      profiles.hours.add(
          new OperatingHoursRecord(
              Ids.newId(), PID, d, LocalTime.of(9, 0), LocalTime.of(21, 0), false));
    }
    pincodes.put("560034", new PincodeRecord("560034", "29", "Karnataka", true));
  }

  private static List<Map<String, Object>> fullWeek() {
    List<Map<String, Object>> hours = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      boolean closed = d >= 5;
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("day_of_week", d);
      entry.put("is_closed", closed);
      if (!closed) {
        entry.put("open_time", "09:00");
        entry.put("close_time", "21:00");
      }
      hours.add(entry);
    }
    return hours;
  }

  private static MedmatePrincipal owner() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "jti");
  }

  private static ProfileRecord copy(ProfileRecord p, String status) {
    return new ProfileRecord(
        p.id(),
        p.code(),
        p.businessName(),
        p.tagline(),
        p.logoUrl(),
        p.phone(),
        p.email(),
        p.pendingPhone(),
        p.pendingEmail(),
        p.businessType(),
        p.address(),
        status,
        p.plan(),
        p.gstin(),
        p.panNumber(),
        p.drugLicenceNumber(),
        p.fssaiNumber(),
        p.isGstRegistered(),
        p.eInvoicingEnabled(),
        p.tdsApplicable(),
        p.tcsApplicable(),
        p.gstinReverificationPending(),
        p.registeredPharmacistName(),
        p.createdAt(),
        p.updatedAt());
  }

  static final class FakeProfileStore implements PharmacyProfileStore {
    ProfileRecord record;
    List<OperatingHoursRecord> hours = new ArrayList<>();
    BankAccountRecord bank;

    ProfileRecord get() {
      return record;
    }

    @Override
    public Optional<ProfileRecord> findById(UUID pharmacyId) {
      return record != null && record.id().equals(pharmacyId)
          ? Optional.of(record)
          : Optional.empty();
    }

    @Override
    public void updateProfileFields(
        UUID pharmacyId,
        String tagline,
        String logoUrl,
        Map<String, Object> address,
        Instant updatedAt) {
      record =
          new ProfileRecord(
              record.id(),
              record.code(),
              record.businessName(),
              tagline != null ? tagline : record.tagline(),
              logoUrl != null ? logoUrl : record.logoUrl(),
              record.phone(),
              record.email(),
              record.pendingPhone(),
              record.pendingEmail(),
              record.businessType(),
              address != null ? address : record.address(),
              record.status(),
              record.plan(),
              record.gstin(),
              record.panNumber(),
              record.drugLicenceNumber(),
              record.fssaiNumber(),
              record.isGstRegistered(),
              record.eInvoicingEnabled(),
              record.tdsApplicable(),
              record.tcsApplicable(),
              record.gstinReverificationPending(),
              record.registeredPharmacistName(),
              record.createdAt(),
              updatedAt);
    }

    @Override
    public void setPendingPhone(UUID pharmacyId, String pendingPhone, Instant updatedAt) {
      record = copyPending(record, pendingPhone, record.pendingEmail(), updatedAt);
    }

    @Override
    public void setPendingEmail(UUID pharmacyId, String pendingEmail, Instant updatedAt) {
      record = copyPending(record, record.pendingPhone(), pendingEmail, updatedAt);
    }

    @Override
    public void applyPhone(UUID pharmacyId, String phone, Instant updatedAt) {
      record =
          new ProfileRecord(
              record.id(),
              record.code(),
              record.businessName(),
              record.tagline(),
              record.logoUrl(),
              phone,
              record.email(),
              null,
              record.pendingEmail(),
              record.businessType(),
              record.address(),
              record.status(),
              record.plan(),
              record.gstin(),
              record.panNumber(),
              record.drugLicenceNumber(),
              record.fssaiNumber(),
              record.isGstRegistered(),
              record.eInvoicingEnabled(),
              record.tdsApplicable(),
              record.tcsApplicable(),
              record.gstinReverificationPending(),
              record.registeredPharmacistName(),
              record.createdAt(),
              updatedAt);
    }

    @Override
    public void applyEmail(UUID pharmacyId, String email, Instant updatedAt) {
      record =
          new ProfileRecord(
              record.id(),
              record.code(),
              record.businessName(),
              record.tagline(),
              record.logoUrl(),
              record.phone(),
              email,
              record.pendingPhone(),
              null,
              record.businessType(),
              record.address(),
              record.status(),
              record.plan(),
              record.gstin(),
              record.panNumber(),
              record.drugLicenceNumber(),
              record.fssaiNumber(),
              record.isGstRegistered(),
              record.eInvoicingEnabled(),
              record.tdsApplicable(),
              record.tcsApplicable(),
              record.gstinReverificationPending(),
              record.registeredPharmacistName(),
              record.createdAt(),
              updatedAt);
    }

    @Override
    public void updateTaxFields(
        UUID pharmacyId,
        String gstin,
        String panNumber,
        String drugLicenceNumber,
        String fssaiNumber,
        Boolean isGstRegistered,
        Boolean eInvoicingEnabled,
        Boolean tdsApplicable,
        Boolean tcsApplicable,
        String registeredPharmacistName,
        boolean gstinReverificationPending,
        Instant updatedAt) {
      record =
          new ProfileRecord(
              record.id(),
              record.code(),
              record.businessName(),
              record.tagline(),
              record.logoUrl(),
              record.phone(),
              record.email(),
              record.pendingPhone(),
              record.pendingEmail(),
              record.businessType(),
              record.address(),
              record.status(),
              record.plan(),
              gstin != null ? gstin : record.gstin(),
              panNumber != null ? panNumber : record.panNumber(),
              drugLicenceNumber != null ? drugLicenceNumber : record.drugLicenceNumber(),
              fssaiNumber != null ? fssaiNumber : record.fssaiNumber(),
              isGstRegistered != null ? isGstRegistered : record.isGstRegistered(),
              eInvoicingEnabled != null ? eInvoicingEnabled : record.eInvoicingEnabled(),
              tdsApplicable != null ? tdsApplicable : record.tdsApplicable(),
              tcsApplicable != null ? tcsApplicable : record.tcsApplicable(),
              gstinReverificationPending,
              registeredPharmacistName != null
                  ? registeredPharmacistName
                  : record.registeredPharmacistName(),
              record.createdAt(),
              updatedAt);
    }

    @Override
    public void replaceOperatingHours(
        UUID pharmacyId, List<OperatingHoursRecord> newHours, Instant now) {
      hours = new ArrayList<>(newHours);
    }

    @Override
    public List<OperatingHoursRecord> listOperatingHours(UUID pharmacyId) {
      return List.copyOf(hours);
    }

    @Override
    public Optional<BankAccountRecord> findActiveBankAccount(UUID pharmacyId) {
      return Optional.ofNullable(bank);
    }

    @Override
    public void softDeleteBankAccount(UUID bankAccountId, Instant deletedAt) {
      if (bank != null && bank.id().equals(bankAccountId)) {
        bank = null;
      }
    }

    @Override
    public void insertBankAccount(BankAccountRecord rec) {
      bank = rec;
    }

    @Override
    public void updateBankVerification(
        UUID bankAccountId,
        String verificationStatus,
        String pennyDropReference,
        Instant verifiedAt,
        Instant updatedAt) {
      bank =
          new BankAccountRecord(
              bank.id(),
              bank.pharmacyId(),
              bank.accountHolder(),
              bank.bankName(),
              bank.accountNumberEncrypted(),
              bank.accountNumberLast4(),
              bank.ifscCode(),
              bank.accountType(),
              verificationStatus,
              pennyDropReference,
              verifiedAt,
              bank.createdAt(),
              updatedAt);
    }

    @Override
    public List<BankAccountRecord> findStalePendingBankAccounts(Instant createdBefore, int limit) {
      if (bank != null
          && "PENDING".equals(bank.verificationStatus())
          && bank.createdAt().isBefore(createdBefore)) {
        return List.of(bank);
      }
      return List.of();
    }

    @Override
    public void updateBusinessName(UUID pharmacyId, String businessName, Instant updatedAt) {
      record =
          new ProfileRecord(
              record.id(),
              record.code(),
              businessName,
              record.tagline(),
              record.logoUrl(),
              record.phone(),
              record.email(),
              record.pendingPhone(),
              record.pendingEmail(),
              record.businessType(),
              record.address(),
              record.status(),
              record.plan(),
              record.gstin(),
              record.panNumber(),
              record.drugLicenceNumber(),
              record.fssaiNumber(),
              record.isGstRegistered(),
              record.eInvoicingEnabled(),
              record.tdsApplicable(),
              record.tcsApplicable(),
              record.gstinReverificationPending(),
              record.registeredPharmacistName(),
              record.createdAt(),
              updatedAt);
    }

    @Override
    public void updateTagline(UUID pharmacyId, String tagline, Instant updatedAt) {
      updateProfileFields(pharmacyId, tagline, null, null, updatedAt);
    }

    @Override
    public void updateLogoUrl(UUID pharmacyId, String logoUrl, Instant updatedAt) {
      updateProfileFields(pharmacyId, null, logoUrl, null, updatedAt);
    }

    @Override
    public void updateAddress(UUID pharmacyId, Map<String, Object> address, Instant updatedAt) {
      updateProfileFields(pharmacyId, null, null, address, updatedAt);
    }

    @Override
    public void updatePhone(UUID pharmacyId, String phone, Instant updatedAt) {
      applyPhone(pharmacyId, phone, updatedAt);
    }

    @Override
    public void updateEmail(UUID pharmacyId, String email, Instant updatedAt) {
      applyEmail(pharmacyId, email, updatedAt);
    }

    private static ProfileRecord copyPending(
        ProfileRecord r, String pendingPhone, String pendingEmail, Instant updatedAt) {
      return new ProfileRecord(
          r.id(),
          r.code(),
          r.businessName(),
          r.tagline(),
          r.logoUrl(),
          r.phone(),
          r.email(),
          pendingPhone,
          pendingEmail,
          r.businessType(),
          r.address(),
          r.status(),
          r.plan(),
          r.gstin(),
          r.panNumber(),
          r.drugLicenceNumber(),
          r.fssaiNumber(),
          r.isGstRegistered(),
          r.eInvoicingEnabled(),
          r.tdsApplicable(),
          r.tcsApplicable(),
          r.gstinReverificationPending(),
          r.registeredPharmacistName(),
          r.createdAt(),
          updatedAt);
    }
  }

  static final class FakeRegistrationStore implements PharmacyRegistrationStore {
    @Override
    public void insert(PharmacyRecord pharmacy) {}

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public boolean existsGstin(String gstin) {
      return false;
    }

    @Override
    public boolean existsPan(String pan) {
      return false;
    }

    @Override
    public boolean existsDrugLicence(String licence, String stateCode) {
      return false;
    }

    @Override
    public boolean existsPhone(String phone) {
      return false;
    }

    @Override
    public boolean existsEmail(String email) {
      return false;
    }

    @Override
    public void markEmailVerified(UUID pharmacyId, Instant at) {}

    @Override
    public void updateStatus(
        UUID pharmacyId, String status, Instant kycSubmittedAt, Instant updatedAt) {}

    @Override
    public void activateAfterAutoKyc(UUID pharmacyId, UUID zoneId, Instant at) {}
  }

  static final class FakeChangeRequests implements ProfileChangeRequestStore {
    final List<ChangeRequestRecord> records = new ArrayList<>();

    @Override
    public void insert(ChangeRequestRecord record) {
      records.add(record);
    }
  }

  static final class FakeProfileOtps implements PharmacyProfileOtpStore {
    OtpRecord latest;

    @Override
    public void insert(OtpRecord record) {
      latest = record;
    }

    @Override
    public void update(OtpRecord record) {
      latest = record;
    }

    @Override
    public void deleteByPharmacyAndChannel(UUID pharmacyId, String channel) {
      latest = null;
    }

    @Override
    public Optional<OtpRecord> findLatest(UUID pharmacyId, String channel) {
      return Optional.ofNullable(latest);
    }
  }

  static final class FakePincodes implements PincodeReferenceStore {
    final Map<String, PincodeRecord> map = new ConcurrentHashMap<>();

    void put(String pin, PincodeRecord rec) {
      map.put(pin, rec);
    }

    @Override
    public Optional<PincodeRecord> findServiceable(String pincode) {
      PincodeRecord rec = map.get(pincode);
      return rec != null && rec.serviceable() ? Optional.of(rec) : Optional.empty();
    }
  }

  static final class FakeAudit implements AuditLogStore {
    final List<AuditLogRecord> records = new ArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      records.add(record);
    }
  }
}
