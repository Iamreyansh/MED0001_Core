package com.nammamedmate.pharmacy.domain;

import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** BR6: 13 equally weighted profile fields. */
public final class ProfileCompleteness {

  static final int FIELD_COUNT = 13;
  static final int IMPACT_PCT = (int) Math.round(100.0 / FIELD_COUNT);

  private ProfileCompleteness() {}

  public record Result(
      int completenessPct, List<String> completedFields, List<Map<String, Object>> missingFields) {
    public Result {
      completedFields = List.copyOf(completedFields);
      missingFields = List.copyOf(missingFields);
    }
  }

  public static Result calculate(
      ProfileRecord profile, List<OperatingHoursRecord> hours, BankAccountRecord bank) {
    List<String> completed = new ArrayList<>();
    List<Map<String, Object>> missing = new ArrayList<>();

    checkField(
        completed,
        missing,
        "business_name",
        "Business Name",
        isFilled(profile.businessName()),
        "Enter your pharmacy business name.");
    checkField(
        completed,
        missing,
        "phone",
        "Phone Number",
        isFilled(profile.phone()),
        "Add a verified phone number for customer contact.");
    checkField(
        completed,
        missing,
        "email",
        "Email Address",
        isFilled(profile.email()),
        "Add your business email address.");
    checkField(
        completed,
        missing,
        "logo_url",
        "Pharmacy Logo",
        isFilled(profile.logoUrl()),
        "Upload your pharmacy logo to build customer trust.");
    boolean addressComplete = isAddressComplete(profile.address());
    checkField(
        completed,
        missing,
        "address",
        "Complete Address",
        addressComplete,
        "Fill in flat, area, city, state, and pincode.");
    checkField(
        completed,
        missing,
        "gstin",
        "GSTIN",
        isFilled(profile.gstin()),
        "Add your GSTIN for tax compliance.");
    checkField(
        completed,
        missing,
        "drug_licence_number",
        "Drug Licence Number",
        isFilled(profile.drugLicenceNumber()),
        "Add your drug licence number.");
    checkField(
        completed,
        missing,
        "fssai_number",
        "FSSAI Number",
        isFilled(profile.fssaiNumber()),
        "Add your FSSAI licence number for compliance.");
    checkField(
        completed,
        missing,
        "pan_number",
        "PAN Number",
        isFilled(profile.panNumber()),
        "Add your PAN number.");
    boolean hoursOk = countOpenDays(hours) >= 5;
    checkField(
        completed,
        missing,
        "operating_hours",
        "Operating Hours",
        hoursOk,
        "Configure operating hours for at least 5 days of the week.");
    boolean bankVerified = bank != null && "VERIFIED".equals(bank.verificationStatus());
    if (bankVerified) {
      completed.add("bank_account_verified");
    } else {
      missing.add(
          missingEntry(
              "bank_account",
              "Verified Bank Account",
              "Add and verify your bank account for payouts."));
    }
    checkField(
        completed,
        missing,
        "tagline",
        "Tagline",
        isFilled(profile.tagline()),
        "Add a short tagline to describe your pharmacy.");
    checkField(
        completed,
        missing,
        "registered_pharmacist_name",
        "Registered Pharmacist",
        isFilled(profile.registeredPharmacistName()),
        "Add the name of your registered pharmacist.");

    int pct = (int) Math.round(100.0 * completed.size() / FIELD_COUNT);
    return new Result(pct, List.copyOf(completed), List.copyOf(missing));
  }

  private static void checkField(
      List<String> completed,
      List<Map<String, Object>> missing,
      String field,
      String label,
      boolean done,
      String action) {
    if (done) {
      completed.add(field);
    } else {
      missing.add(missingEntry(field, label, action));
    }
  }

  private static Map<String, Object> missingEntry(String field, String label, String action) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("field", field);
    m.put("label", label);
    m.put("impact_pct", IMPACT_PCT);
    m.put("action", action);
    return m;
  }

  static boolean isAddressComplete(Map<String, Object> address) {
    if (address == null || address.isEmpty()) {
      return false;
    }
    return isFilled(str(address.get("flat")))
        && isFilled(str(address.get("area")))
        && isFilled(str(address.get("city")))
        && isFilled(str(address.get("state")))
        && isFilled(str(address.get("pincode")));
  }

  static int countOpenDays(List<OperatingHoursRecord> hours) {
    if (hours == null) {
      return 0;
    }
    int open = 0;
    for (OperatingHoursRecord h : hours) {
      if (!h.closed()) {
        open++;
      }
    }
    return open;
  }

  private static boolean isFilled(String value) {
    return value != null && !value.isBlank();
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }
}
