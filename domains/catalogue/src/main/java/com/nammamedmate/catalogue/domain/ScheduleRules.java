package com.nammamedmate.catalogue.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only schedule classification rules (platform config, not DB). */
public final class ScheduleRules {

  private ScheduleRules() {}

  public static List<Map<String, Object>> all() {
    return List.of(otc(), h(), h1(), x());
  }

  private static Map<String, Object> otc() {
    Map<String, Object> m =
        base(
            "OTC",
            "Over-the-Counter",
            "Medicines that can be sold without a prescription.",
            false,
            false,
            true,
            List.of("Paracetamol 500mg", "Antacid tablets", "Vitamin C supplements"),
            "Drugs and Cosmetics Act, Schedule K");
    return Map.copyOf(m);
  }

  private static Map<String, Object> h() {
    Map<String, Object> m =
        base(
            "H",
            "Schedule H",
            "Prescription-only medicines including antibiotics, antihypertensives, and antidiabetics.",
            true,
            false,
            true,
            List.of("Augmentin 625", "Metformin 500mg", "Amlodipine 5mg"),
            "Drugs and Cosmetics Act, Schedule H");
    return Map.copyOf(m);
  }

  private static Map<String, Object> h1() {
    Map<String, Object> m =
        base(
            "H1",
            "Schedule H1",
            "Third-generation cephalosporins, carbapenems, and sulphonamides requiring pharmacist register.",
            true,
            true,
            true,
            List.of("Ceftriaxone 1g Injection", "Imipenem-Cilastatin", "Chloramphenicol"),
            "Drugs and Cosmetics (Amendment) Rules 2013, Schedule H1");
    m.put("register_name", "Schedule H1 Dispensing Register");
    return Map.copyOf(m);
  }

  private static Map<String, Object> x() {
    Map<String, Object> m =
        base(
            "X",
            "Schedule X",
            "Narcotic and psychotropic substances under NDPS Act. Triplicate Rx, patient ID verification. NOT available for online delivery.",
            true,
            true,
            false,
            List.of("Morphine Sulphate", "Codeine Phosphate", "Alprazolam"),
            "NDPS Act 1985, Narcotic Drugs and Psychotropic Substances Rules 1985");
    m.put("prescription_type", "TRIPLICATE");
    m.put("register_name", "Narcotic Drugs Register");
    m.put("patient_id_verification", true);
    return Map.copyOf(m);
  }

  private static Map<String, Object> base(
      String schedule,
      String fullName,
      String description,
      boolean prescriptionRequired,
      boolean specialRegisterRequired,
      boolean onlineDeliveryAllowed,
      List<String> examples,
      String regulatoryReference) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schedule", schedule);
    m.put("full_name", fullName);
    m.put("description", description);
    m.put("prescription_required", prescriptionRequired);
    m.put("special_register_required", specialRegisterRequired);
    m.put("online_delivery_allowed", onlineDeliveryAllowed);
    m.put("examples", List.copyOf(examples));
    m.put("regulatory_reference", regulatoryReference);
    return m;
  }
}
