package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
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
import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore;
import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore.ChangeRequestRecord;
import com.nammamedmate.pharmacy.application.port.out.ProfileContactNotifier;
import com.nammamedmate.pharmacy.domain.Gstin;
import com.nammamedmate.pharmacy.domain.IndianPhone;
import com.nammamedmate.pharmacy.domain.LogoUrlValidator;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.pharmacy.domain.MagicRegistrationOtp;
import com.nammamedmate.pharmacy.domain.OperatingHoursValidator;
import com.nammamedmate.pharmacy.domain.Pan;
import com.nammamedmate.pharmacy.domain.ProfileCompleteness;
import com.nammamedmate.pharmacy.domain.ProfileCompleteness.Result;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyProfileService {

  static final int GET_LIMIT = 120;
  static final int PATCH_LIMIT = 20;
  static final int TAX_LIMIT = 10;
  static final int COMPLETENESS_LIMIT = 60;
  static final int BANK_POST_LIMIT = 5;
  static final int BANK_GET_LIMIT = 60;
  static final int VERIFY_CONTACT_LIMIT = 10;
  static final int WINDOW = 60;
  static final int OTP_MAX_ATTEMPTS = 5;
  static final Duration OTP_TTL = Duration.ofMinutes(10);
  static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
  static final Pattern ACCOUNT_NUMBER = Pattern.compile("^\\d{9,18}$");

  private final PharmacyProfileStore profiles;
  private final PharmacyRegistrationStore pharmacies;
  private final ProfileChangeRequestStore changeRequests;
  private final PharmacyProfileOtpStore profileOtps;
  private final PincodeReferenceStore pincodes;
  private final PennyDropPort pennyDrop;
  private final AesGcmCipher cipher;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final PasswordEncoder otpEncoder;
  private final Supplier<String> otpGenerator;
  private final SecureRandom secureRandom;
  private final Clock clock;
  private final ProfileContactNotifier contactNotifier;

  @Autowired
  public PharmacyProfileService(
      PharmacyProfileStore profiles,
      PharmacyRegistrationStore pharmacies,
      ProfileChangeRequestStore changeRequests,
      PharmacyProfileOtpStore profileOtps,
      PincodeReferenceStore pincodes,
      PennyDropPort pennyDrop,
      @Qualifier("bankAccountCipher") AesGcmCipher cipher,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      @Qualifier("passwordEncoder") PasswordEncoder otpEncoder,
      Clock clock,
      ProfileContactNotifier contactNotifier) {
    this(
        profiles,
        pharmacies,
        changeRequests,
        profileOtps,
        pincodes,
        pennyDrop,
        cipher,
        outbox,
        rateLimiter,
        otpEncoder,
        null,
        new SecureRandom(),
        clock,
        contactNotifier);
  }

  /** Test ctor — NOOP contact notifier. */
  PharmacyProfileService(
      PharmacyProfileStore profiles,
      PharmacyRegistrationStore pharmacies,
      ProfileChangeRequestStore changeRequests,
      PharmacyProfileOtpStore profileOtps,
      PincodeReferenceStore pincodes,
      PennyDropPort pennyDrop,
      @Qualifier("bankAccountCipher") AesGcmCipher cipher,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      @Qualifier("passwordEncoder") PasswordEncoder otpEncoder,
      Clock clock) {
    this(
        profiles,
        pharmacies,
        changeRequests,
        profileOtps,
        pincodes,
        pennyDrop,
        cipher,
        outbox,
        rateLimiter,
        otpEncoder,
        null,
        new SecureRandom(),
        clock,
        ProfileContactNotifier.NOOP);
  }

  PharmacyProfileService(
      PharmacyProfileStore profiles,
      PharmacyRegistrationStore pharmacies,
      ProfileChangeRequestStore changeRequests,
      PharmacyProfileOtpStore profileOtps,
      PincodeReferenceStore pincodes,
      PennyDropPort pennyDrop,
      @Qualifier("bankAccountCipher") AesGcmCipher cipher,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      @Qualifier("passwordEncoder") PasswordEncoder otpEncoder,
      Supplier<String> otpGenerator,
      SecureRandom secureRandom,
      Clock clock) {
    this(
        profiles,
        pharmacies,
        changeRequests,
        profileOtps,
        pincodes,
        pennyDrop,
        cipher,
        outbox,
        rateLimiter,
        otpEncoder,
        otpGenerator,
        secureRandom,
        clock,
        ProfileContactNotifier.NOOP);
  }

  PharmacyProfileService(
      PharmacyProfileStore profiles,
      PharmacyRegistrationStore pharmacies,
      ProfileChangeRequestStore changeRequests,
      PharmacyProfileOtpStore profileOtps,
      PincodeReferenceStore pincodes,
      PennyDropPort pennyDrop,
      AesGcmCipher cipher,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      PasswordEncoder otpEncoder,
      Supplier<String> otpGenerator,
      SecureRandom secureRandom,
      Clock clock,
      ProfileContactNotifier contactNotifier) {
    this.profiles = profiles;
    this.pharmacies = pharmacies;
    this.changeRequests = changeRequests;
    this.profileOtps = profileOtps;
    this.pincodes = pincodes;
    this.pennyDrop = pennyDrop;
    this.cipher = cipher;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.otpEncoder = otpEncoder;
    this.otpGenerator = otpGenerator;
    this.secureRandom = secureRandom;
    this.clock = clock;
    this.contactNotifier = contactNotifier == null ? ProfileContactNotifier.NOOP : contactNotifier;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getProfile(MedmatePrincipal principal) {
    requirePharmacyRole(principal);
    rateLimit("pharmacy:profile:get:" + principal.pharmacyId(), GET_LIMIT);
    ProfileRecord p = requireActiveProfile(principal.pharmacyId());
    return toProfileMap(p);
  }

  @Transactional
  public Map<String, Object> patchProfile(MedmatePrincipal principal, Map<String, Object> body) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pharmacy:profile:patch:" + pharmacyId, PATCH_LIMIT);
    ProfileRecord current = requireActiveProfile(pharmacyId);
    Instant now = clock.instant();

    List<String> updated = new ArrayList<>();
    List<String> pendingApproval = new ArrayList<>();
    List<String> pendingVerification = new ArrayList<>();

    if (body.containsKey("business_name")) {
      String newName = str(body.get("business_name"));
      if (newName != null && !newName.equals(current.businessName())) {
        validateBusinessName(newName);
        UUID changeId = Ids.newId();
        changeRequests.insert(
            new ChangeRequestRecord(
                changeId,
                pharmacyId,
                "business_name",
                current.businessName() == null ? "" : current.businessName(),
                newName,
                "PENDING_APPROVAL",
                null,
                null,
                now));
        pendingApproval.add("business_name");
        outbox.publish(
            DomainEvent.of(
                "pharmacy.notification.profile_change_pending",
                "pharmacy",
                pharmacyId,
                Map.of(
                    "pharmacy_id",
                    pharmacyId.toString(),
                    "change_request_id",
                    changeId.toString(),
                    "field",
                    "business_name")));
      }
    }

    String tagline = body.containsKey("tagline") ? str(body.get("tagline")) : null;
    if (tagline != null) {
      if (tagline.length() > 200) {
        throw new AppException("VALIDATION_ERROR", "tagline max 200 chars", 400);
      }
      updated.add("tagline");
    }

    String logoUrl = body.containsKey("logo_url") ? str(body.get("logo_url")) : null;
    if (logoUrl != null) {
      LogoUrlValidator.requireValid(logoUrl);
      updated.add("logo_url");
    }

    if (body.containsKey("phone")) {
      String newPhone = validatePhone(str(body.get("phone")));
      if (!newPhone.equals(current.phone())) {
        if (pharmacies.existsPhone(newPhone)) {
          throw new AppException("PHONE_ALREADY_REGISTERED", "Phone already in use", 409);
        }
        sendProfileOtp(pharmacyId, "PHONE", newPhone, now);
        profiles.setPendingPhone(pharmacyId, newPhone, now);
        pendingVerification.add("phone");
      }
    }

    if (body.containsKey("email")) {
      String newEmail = validateEmail(str(body.get("email")));
      if (!newEmail.equalsIgnoreCase(current.email())) {
        if (pharmacies.existsEmail(newEmail)) {
          throw new AppException("EMAIL_ALREADY_REGISTERED", "Email already in use", 409);
        }
        sendProfileOtp(pharmacyId, "EMAIL", newEmail.toLowerCase(), now);
        profiles.setPendingEmail(pharmacyId, newEmail.toLowerCase(), now);
        pendingVerification.add("email");
      }
    }

    Map<String, Object> mergedAddress = null;
    if (body.containsKey("address") && body.get("address") instanceof Map<?, ?> rawAddr) {
      mergedAddress = mergeAddress(current.address(), rawAddr);
      if (mergedAddress.containsKey("pincode")) {
        validatePincode(str(mergedAddress.get("pincode")));
      }
      updated.add("address");
    }

    List<OperatingHoursRecord> hours = null;
    if (body.containsKey("operating_hours") && body.get("operating_hours") instanceof List<?> raw) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> hourMaps = (List<Map<String, Object>>) raw;
      OperatingHoursValidator.requireValid(hourMaps);
      hours = toHoursRecords(pharmacyId, hourMaps);
      updated.add("operating_hours");
    }

    if (tagline != null || logoUrl != null || mergedAddress != null) {
      profiles.updateProfileFields(pharmacyId, tagline, logoUrl, mergedAddress, now);
    }
    if (hours != null) {
      profiles.replaceOperatingHours(pharmacyId, hours, now);
    }

    ProfileRecord refreshed = profiles.findById(pharmacyId).orElse(current);
    Result completeness = completeness(refreshed);

    String message = buildPatchMessage(pendingApproval, pendingVerification);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("updated_fields", updated);
    data.put("pending_approval_fields", pendingApproval);
    data.put("pending_verification_fields", pendingVerification);
    data.put("profile_completeness_pct", completeness.completenessPct());
    data.put("message", message);
    return data;
  }

  @Transactional
  public Map<String, Object> patchTax(MedmatePrincipal principal, Map<String, Object> body) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pharmacy:profile:tax:" + pharmacyId, TAX_LIMIT);
    ProfileRecord current = requireActiveProfile(pharmacyId);
    Instant now = clock.instant();

    List<String> updated = new ArrayList<>();
    boolean reVerify = false;

    String gstin = null;
    if (body.containsKey("gstin")) {
      gstin = parseGstin(str(body.get("gstin")));
      if (!gstin.equals(current.gstin())) {
        reVerify = true;
        updated.add("gstin");
      } else {
        gstin = null;
      }
    }

    String pan = null;
    if (body.containsKey("pan_number")) {
      pan = parsePan(str(body.get("pan_number")));
      updated.add("pan_number");
    }

    String drugLicence =
        body.containsKey("drug_licence_number") ? str(body.get("drug_licence_number")) : null;
    if (drugLicence != null) {
      if (drugLicence.length() > 50) {
        throw new AppException("VALIDATION_ERROR", "drug_licence_number max 50 chars", 400);
      }
      updated.add("drug_licence_number");
    }

    String fssai = body.containsKey("fssai_number") ? str(body.get("fssai_number")) : null;
    if (fssai != null) {
      if (!fssai.matches("^\\d{14}$")) {
        throw new AppException("VALIDATION_ERROR", "fssai_number must be 14 digits", 400);
      }
      updated.add("fssai_number");
    }

    Optional<Boolean> isGstRegistered = asBool(body.get("is_gst_registered"));
    if (isGstRegistered.isPresent()) {
      updated.add("is_gst_registered");
    }
    Optional<Boolean> eInvoicing = asBool(body.get("e_invoicing_enabled"));
    if (eInvoicing.isPresent()) {
      updated.add("e_invoicing_enabled");
    }
    Optional<Boolean> tds = asBool(body.get("tds_applicable"));
    if (tds.isPresent()) {
      updated.add("tds_applicable");
    }
    Optional<Boolean> tcs = asBool(body.get("tcs_applicable"));
    if (tcs.isPresent()) {
      updated.add("tcs_applicable");
    }

    String pharmacist =
        body.containsKey("registered_pharmacist_name")
            ? str(body.get("registered_pharmacist_name"))
            : null;
    if (pharmacist != null) {
      if (pharmacist.length() > 100) {
        throw new AppException("VALIDATION_ERROR", "registered_pharmacist_name max 100 chars", 400);
      }
      updated.add("registered_pharmacist_name");
    }

    profiles.updateTaxFields(
        pharmacyId,
        gstin,
        pan,
        drugLicence,
        fssai,
        isGstRegistered.orElse(null),
        eInvoicing.orElse(null),
        tds.orElse(null),
        tcs.orElse(null),
        pharmacist,
        reVerify || current.gstinReverificationPending(),
        now);

    ProfileRecord refreshed = profiles.findById(pharmacyId).orElse(current);
    Result completeness = completeness(refreshed);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("updated_fields", updated);
    data.put("re_verification_triggered", reVerify);
    data.put("profile_completeness_pct", completeness.completenessPct());
    data.put("message", "Tax details updated successfully.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getCompleteness(MedmatePrincipal principal) {
    requirePharmacyRole(principal);
    rateLimit("pharmacy:profile:completeness:" + principal.pharmacyId(), COMPLETENESS_LIMIT);
    ProfileRecord p = requireActiveProfile(principal.pharmacyId());
    Result result = completeness(p);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", p.id().toString());
    data.put("completeness_pct", result.completenessPct());
    data.put("missing_fields", result.missingFields());
    data.put("completed_fields", result.completedFields());
    return data;
  }

  @Transactional
  public Map<String, Object> saveBankAccount(MedmatePrincipal principal, Map<String, Object> body) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pharmacy:profile:bank-post:" + pharmacyId, BANK_POST_LIMIT);
    requireActiveProfile(pharmacyId);

    BankAccountRecord existing = profiles.findActiveBankAccount(pharmacyId).orElse(null);
    if (existing != null && "VERIFIED".equals(existing.verificationStatus())) {
      throw new AppException(
          "BANK_ACCOUNT_ALREADY_VERIFIED",
          "A verified bank account already exists; contact support to change",
          409);
    }
    Instant now = clock.instant();
    if (existing != null) {
      profiles.softDeleteBankAccount(existing.id(), now);
    }

    String holder = requireStr(body.get("account_holder"), "account_holder");
    if (holder.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "account_holder max 100 chars", 400);
    }
    String bankName = requireStr(body.get("bank_name"), "bank_name");
    if (bankName.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "bank_name max 100 chars", 400);
    }
    String accountNumber = requireStr(body.get("account_number"), "account_number");
    if (!ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
      throw new AppException("INVALID_ACCOUNT_NUMBER", "Account number must be 9-18 digits", 400);
    }
    String ifsc = requireStr(body.get("ifsc_code"), "ifsc_code").toUpperCase();
    // ponytail: format validation only; full RBI IFSC registry lookup later
    if (!IFSC.matcher(ifsc).matches()) {
      throw new AppException("INVALID_IFSC", "IFSC code format is invalid", 400);
    }
    String accountType = requireStr(body.get("account_type"), "account_type").toUpperCase();
    if (!"CURRENT".equals(accountType) && !"SAVINGS".equals(accountType)) {
      throw new AppException("VALIDATION_ERROR", "account_type must be CURRENT or SAVINGS", 400);
    }

    String last4 = accountNumber.substring(accountNumber.length() - 4);
    String encrypted = cipher.encrypt(accountNumber);
    UUID bankId = Ids.newId();

    PennyDropResult penny = pennyDrop.initiate(pharmacyId, ifsc, last4);
    profiles.insertBankAccount(
        new BankAccountRecord(
            bankId,
            pharmacyId,
            holder,
            bankName,
            encrypted,
            last4,
            ifsc,
            accountType,
            "PENDING",
            penny.referenceId(),
            null,
            now,
            now));

    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.bank_verification_pending",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "bank_account_id",
                bankId.toString(),
                "penny_drop_reference",
                penny.referenceId())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bank_account_id", bankId.toString());
    data.put("account_holder", holder);
    data.put("bank_name", bankName);
    data.put("account_number_masked", maskAccount(last4));
    data.put("ifsc_code", ifsc);
    data.put("account_type", accountType);
    data.put("verification_status", "PENDING");
    data.put("penny_drop_initiated", true);
    data.put("estimated_verification_hours", 24);
    data.put(
        "message",
        "Bank account saved. A Re 1 test transfer has been initiated. You will be notified once verified.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getBankAccount(MedmatePrincipal principal) {
    requireBankRead(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pharmacy:profile:bank-get:" + pharmacyId, BANK_GET_LIMIT);
    BankAccountRecord bank =
        profiles
            .findActiveBankAccount(pharmacyId)
            .orElseThrow(
                () -> new AppException("BANK_ACCOUNT_NOT_FOUND", "No bank account saved", 404));
    return bankToMap(bank);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getBankAccountForAdmin(UUID pharmacyId) {
    BankAccountRecord bank =
        profiles
            .findActiveBankAccount(pharmacyId)
            .orElseThrow(
                () -> new AppException("BANK_ACCOUNT_NOT_FOUND", "No bank account saved", 404));
    return bankToMap(bank);
  }

  public List<OperatingHoursRecord> mapOperatingHours(
      UUID pharmacyId, List<Map<String, Object>> raw) {
    return toHoursRecords(pharmacyId, raw);
  }

  @Transactional
  public Map<String, Object> verifyContact(
      MedmatePrincipal principal, String channel, String otpRaw) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pharmacy:profile:verify-contact:" + pharmacyId, VERIFY_CONTACT_LIMIT);
    requireActiveProfile(pharmacyId);
    if (channel == null || (!"PHONE".equals(channel) && !"EMAIL".equals(channel))) {
      throw new AppException("VALIDATION_ERROR", "channel must be PHONE or EMAIL", 400);
    }
    if (otpRaw == null || otpRaw.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", "otp is required", 400);
    }

    ProfileRecord profile = profiles.findById(pharmacyId).orElseThrow();
    String target = "PHONE".equals(channel) ? profile.pendingPhone() : profile.pendingEmail();
    if (target == null || target.isBlank()) {
      throw new AppException(
          "NO_PENDING_VERIFICATION", "No pending " + channel.toLowerCase() + " change", 400);
    }

    OtpRecord otp =
        profileOtps
            .findLatest(pharmacyId, channel)
            .orElseThrow(() -> new AppException("OTP_NOT_FOUND", "OTP not found", 404));

    Instant now = clock.instant();
    if (now.isAfter(otp.expiresAt())) {
      throw new AppException("OTP_EXPIRED", "OTP has expired", 400);
    }
    if (otp.attempts() >= OTP_MAX_ATTEMPTS) {
      throw new AppException("OTP_LOCKED", "Too many failed attempts", 429);
    }

    boolean ok =
        MagicProfileOtp.matches(channel, target, otpRaw)
            || otpEncoder.matches(otpRaw, otp.otpHash());
    if (!ok) {
      profileOtps.update(
          new OtpRecord(
              otp.id(),
              otp.pharmacyId(),
              otp.channel(),
              otp.targetValue(),
              otp.otpHash(),
              otp.expiresAt(),
              otp.attempts() + 1,
              otp.createdAt()));
      throw new AppException("INVALID_OTP", "Invalid OTP", 400);
    }

    if ("PHONE".equals(channel)) {
      profiles.applyPhone(pharmacyId, target, now);
    } else {
      profiles.applyEmail(pharmacyId, target, now);
    }
    profileOtps.deleteByPharmacyAndChannel(pharmacyId, channel);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("channel", channel);
    data.put("verified", true);
    data.put("message", channel + " verified and updated successfully.");
    return data;
  }

  @Transactional
  public void expireStalePennyDrops() {
    Instant cutoff = clock.instant().minus(Duration.ofHours(24));
    Instant now = clock.instant();
    for (BankAccountRecord bank : profiles.findStalePendingBankAccounts(cutoff, 50)) {
      profiles.updateBankVerification(bank.id(), "FAILED", bank.pennyDropReference(), null, now);
      outbox.publish(
          DomainEvent.of(
              "pharmacy.notification.bank_verification_failed",
              "pharmacy",
              bank.pharmacyId(),
              Map.of(
                  "pharmacy_id",
                  bank.pharmacyId().toString(),
                  "bank_account_id",
                  bank.id().toString())));
    }
  }

  private Map<String, Object> toProfileMap(ProfileRecord p) {
    List<OperatingHoursRecord> hours = profiles.listOperatingHours(p.id());
    BankAccountRecord bank = profiles.findActiveBankAccount(p.id()).orElse(null);
    Result completeness = ProfileCompleteness.calculate(p, hours, bank);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", p.id().toString());
    data.put("code", p.code());
    data.put("business_name", p.businessName());
    data.put("tagline", p.tagline());
    data.put("logo_url", p.logoUrl());
    data.put("phone", p.phone());
    data.put("email", p.email());
    data.put("business_type", p.businessType());
    data.put("address", p.address());
    data.put("operating_hours", hoursToMaps(hours));
    data.put("tax", taxToMap(p));
    data.put("bank_account", bank == null ? null : bankSummary(bank));
    data.put("profile_completeness_pct", completeness.completenessPct());
    data.put("status", p.status());
    data.put("plan", p.plan());
    data.put("created_at", p.createdAt() == null ? null : p.createdAt().toString());
    data.put("updated_at", p.updatedAt() == null ? null : p.updatedAt().toString());
    return data;
  }

  private Result completeness(ProfileRecord p) {
    return ProfileCompleteness.calculate(
        p,
        profiles.listOperatingHours(p.id()),
        profiles.findActiveBankAccount(p.id()).orElse(null));
  }

  private static Map<String, Object> taxToMap(ProfileRecord p) {
    Map<String, Object> tax = new LinkedHashMap<>();
    tax.put("gstin", p.gstin());
    tax.put("pan_number", p.panNumber());
    tax.put("drug_licence_number", p.drugLicenceNumber());
    tax.put("fssai_number", p.fssaiNumber());
    tax.put("is_gst_registered", p.isGstRegistered());
    tax.put("e_invoicing_enabled", p.eInvoicingEnabled());
    tax.put("tds_applicable", p.tdsApplicable());
    tax.put("tcs_applicable", p.tcsApplicable());
    tax.put("registered_pharmacist_name", p.registeredPharmacistName());
    return tax;
  }

  private static Map<String, Object> bankSummary(BankAccountRecord bank) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("account_holder", bank.accountHolder());
    m.put("bank_name", bank.bankName());
    m.put("account_number_masked", maskAccount(bank.accountNumberLast4()));
    m.put("ifsc_code", bank.ifscCode());
    m.put("account_type", bank.accountType());
    m.put("verification_status", bank.verificationStatus());
    m.put("verified_at", bank.verifiedAt() == null ? null : bank.verifiedAt().toString());
    return m;
  }

  private static Map<String, Object> bankToMap(BankAccountRecord bank) {
    Map<String, Object> m = bankSummary(bank);
    m.put("bank_account_id", bank.id().toString());
    m.put("penny_drop_reference", bank.pennyDropReference());
    return m;
  }

  private static String maskAccount(String last4) {
    return "XXXXXXXXXXXX" + last4;
  }

  private List<Map<String, Object>> hoursToMaps(List<OperatingHoursRecord> hours) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (OperatingHoursRecord h : hours) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("day_of_week", h.dayOfWeek());
      m.put("day_name", OperatingHoursValidator.dayName(h.dayOfWeek()));
      m.put("open_time", h.closed() || h.openTime() == null ? null : h.openTime().toString());
      m.put("close_time", h.closed() || h.closeTime() == null ? null : h.closeTime().toString());
      m.put("is_closed", h.closed());
      out.add(m);
    }
    return out;
  }

  private List<OperatingHoursRecord> toHoursRecords(
      UUID pharmacyId, List<Map<String, Object>> raw) {
    List<OperatingHoursRecord> list = new ArrayList<>();
    for (Map<String, Object> entry : raw) {
      int day = ((Number) entry.get("day_of_week")).intValue();
      boolean closed = Boolean.TRUE.equals(entry.get("is_closed"));
      LocalTime open = closed ? null : LocalTime.parse(String.valueOf(entry.get("open_time")));
      LocalTime close = closed ? null : LocalTime.parse(String.valueOf(entry.get("close_time")));
      list.add(new OperatingHoursRecord(Ids.newId(), pharmacyId, day, open, close, closed));
    }
    return list;
  }

  private void sendProfileOtp(UUID pharmacyId, String channel, String target, Instant now) {
    profileOtps.deleteByPharmacyAndChannel(pharmacyId, channel);
    String otp = nextOtp(channel, target);
    OtpRecord record =
        new OtpRecord(
            Ids.newId(),
            pharmacyId,
            channel,
            target,
            otpEncoder.encode(otp),
            now.plus(OTP_TTL),
            0,
            now);
    profileOtps.insert(record);
    if ("EMAIL".equals(channel)) {
      contactNotifier.sendEmailOtp(target, otp);
    } else {
      contactNotifier.sendSmsOtp(target, otp);
    }
    // ids only — never OTP or phone/email in durable outbox (match registration)
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.profile_otp",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "otp_id",
                record.id().toString(),
                "channel",
                channel)));
  }

  private String nextOtp(String channel, String target) {
    if ("EMAIL".equals(channel) && MagicRegistrationOtp.isMagicEmail(target)) {
      return MagicProfileOtp.CODE;
    }
    if ("PHONE".equals(channel) && MagicProfileOtp.isTestPhone(target)) {
      return MagicProfileOtp.CODE;
    }
    if (otpGenerator != null) {
      return otpGenerator.get();
    }
    return String.format("%06d", secureRandom.nextInt(1_000_000));
  }

  private ProfileRecord requireActiveProfile(UUID pharmacyId) {
    ProfileRecord p =
        profiles
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "Pharmacy not found", 401));
    if (!"ACTIVE".equals(p.status())) {
      throw new AppException("PHARMACY_NOT_ACTIVE", "Pharmacy is not active", 403);
    }
    return p;
  }

  private static void validateBusinessName(String name) {
    if (name.length() < 2 || name.length() > 120) {
      throw new AppException("VALIDATION_ERROR", "business_name must be 2-120 chars", 400);
    }
  }

  private static String validatePhone(String raw) {
    try {
      return IndianPhone.requireValid(raw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_PHONE", "Phone must be +91XXXXXXXXXX", 400);
    }
  }

  private static String validateEmail(String raw) {
    if (raw == null || !raw.contains("@") || raw.length() > 255) {
      throw new AppException("VALIDATION_ERROR", "Invalid email", 400);
    }
    return raw.trim();
  }

  private void validatePincode(String pincode) {
    if (pincode == null || !pincode.matches("^\\d{6}$")) {
      throw new AppException("INVALID_PINCODE", "Pincode must be 6 digits", 400);
    }
    pincodes
        .findServiceable(pincode)
        .orElseThrow(() -> new AppException("INVALID_PINCODE", "Pincode is not serviceable", 400));
  }

  private static String parseGstin(String raw) {
    try {
      return Gstin.requireValid(raw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_GSTIN", "GSTIN fails format or checksum validation", 400);
    }
  }

  private static String parsePan(String raw) {
    try {
      return Pan.requireValid(raw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_PAN", "PAN format invalid", 400);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mergeAddress(Map<String, Object> current, Map<?, ?> patch) {
    Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
    for (Map.Entry<?, ?> e : patch.entrySet()) {
      if (e.getKey() != null) {
        merged.put(String.valueOf(e.getKey()), e.getValue());
      }
    }
    return merged;
  }

  private static String buildPatchMessage(
      List<String> pendingApproval, List<String> pendingVerification) {
    if (!pendingApproval.isEmpty()) {
      return "Profile updated. "
          + String.join(", ", pendingApproval)
          + " change is pending admin approval.";
    }
    if (!pendingVerification.isEmpty()) {
      return "Profile updated. Verify "
          + String.join(" and ", pendingVerification)
          + " with OTP to complete the change.";
    }
    return "Profile updated successfully.";
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o).trim();
  }

  private static Optional<Boolean> asBool(Object o) {
    return o instanceof Boolean b ? Optional.of(b) : Optional.empty();
  }

  private static String requireStr(Object o, String field) {
    String s = str(o);
    if (s == null || s.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", field + " is required", 400);
    }
    return s;
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  static void requireOwner(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "No pharmacy context", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy owners can perform this action", 403);
    }
  }

  static void requirePharmacyRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "No pharmacy context", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
  }

  private static void requireBankRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    // Pharmacy path is owner-only; admins use GET /admin/pharmacies/{id}/bank-account
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Not authorized to view bank account", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "No pharmacy context", 401);
    }
  }
}
