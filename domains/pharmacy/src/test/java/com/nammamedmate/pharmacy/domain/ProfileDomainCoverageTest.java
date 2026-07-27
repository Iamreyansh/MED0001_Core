package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileDomainCoverageTest {

  @Test
  void profileCompletenessAndValidators() {
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    ProfileRecord sparse =
        new ProfileRecord(
            id,
            "PHM-0001",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "PHARMACY",
            Map.of(),
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            now,
            now);
    ProfileCompleteness.Result emptyAddr = ProfileCompleteness.calculate(sparse, null, null);
    assertThat(emptyAddr.missingFields()).isNotEmpty();

    ProfileRecord nullAddressProfile =
        new ProfileRecord(
            id,
            "PHM-0004",
            "Biz",
            null,
            null,
            null,
            null,
            null,
            null,
            "PHARMACY",
            null,
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            now,
            now);
    assertThat(ProfileCompleteness.calculate(nullAddressProfile, List.of(), null).missingFields())
        .isNotEmpty();
    ProfileRecord nonEmptyIncomplete =
        new ProfileRecord(
            id,
            "PHM-0005",
            "Biz",
            null,
            null,
            null,
            null,
            null,
            null,
            "PHARMACY",
            Map.of("flat", "1"),
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            now,
            now);
    assertThat(ProfileCompleteness.calculate(nonEmptyIncomplete, List.of(), null).missingFields())
        .extracting(m -> m.get("field"))
        .contains("address");
    assertThat(ProfileCompleteness.isAddressComplete(null)).isFalse();
    assertThat(ProfileCompleteness.isAddressComplete(new LinkedHashMap<>())).isFalse();

    Map<String, Object> partialAddr = new LinkedHashMap<>();
    partialAddr.put("flat", "1");
    partialAddr.put("city", "X");
    ProfileRecord partialAddrProfile =
        new ProfileRecord(
            id,
            "PHM-0001",
            "Biz",
            null,
            null,
            null,
            null,
            null,
            null,
            "PHARMACY",
            partialAddr,
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            now,
            now);
    assertThat(ProfileCompleteness.calculate(partialAddrProfile, null, null).missingFields())
        .isNotEmpty();

    Map<String, Object> blankAddr = new LinkedHashMap<>();
    blankAddr.put("flat", " ");
    blankAddr.put("area", "");
    blankAddr.put("city", "C");
    blankAddr.put("state", "S");
    blankAddr.put("pincode", "560034");
    ProfileRecord blankAddrProfile =
        new ProfileRecord(
            id,
            "PHM-0003",
            "Biz",
            null,
            null,
            "+919876543210",
            "a@b.com",
            null,
            null,
            "PHARMACY",
            blankAddr,
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            now,
            now);
    assertThat(ProfileCompleteness.calculate(blankAddrProfile, List.of(), null).missingFields())
        .extracting(m -> m.get("field"))
        .contains("address");

    UUID id2 = Ids.newId();
    for (String missingField : List.of("area", "city", "state", "pincode")) {
      Map<String, Object> addr = new LinkedHashMap<>();
      addr.put("flat", "1");
      addr.put("area", "A");
      addr.put("city", "C");
      addr.put("state", "S");
      addr.put("pincode", "560034");
      addr.remove(missingField);
      ProfileRecord pr =
          new ProfileRecord(
              id2,
              "PHM",
              "B",
              null,
              null,
              null,
              null,
              null,
              null,
              "PHARMACY",
              addr,
              "ACTIVE",
              "FREE",
              null,
              null,
              null,
              null,
              false,
              false,
              false,
              true,
              false,
              null,
              now,
              now);
      assertThat(ProfileCompleteness.calculate(pr, List.of(), null).missingFields())
          .extracting(m -> m.get("field"))
          .contains("address");
    }

    LogoUrlValidator.requireValid(null);
    LogoUrlValidator.requireValid("   ");
    assertThatThrownBy(() -> LogoUrlValidator.requireValid("ftp://x.jpg"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LogoUrlValidator.requireValid("https://x.gif"))
        .isInstanceOf(AppException.class);

    assertThat(MagicProfileOtp.matches("EMAIL", "a@nammamedmate.test", "000000")).isFalse();
    assertThat(MagicProfileOtp.matches("PHONE", "+919811100001", "000000")).isFalse();
    assertThat(MagicProfileOtp.matches("EMAIL", "a@nammamedmate.test", "123456")).isTrue();
    assertThat(MagicProfileOtp.isTestPhone("+919811100001")).isTrue();

    List<Map<String, Object>> bad = new ArrayList<>();
    bad.add(
        Map.of("day_of_week", 0, "open_time", "21:00", "close_time", "09:00", "is_closed", false));
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(bad))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(List.of()))
        .isInstanceOf(AppException.class);
    List<Map<String, Object>> week = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      week.add(
          Map.of(
              "day_of_week", d, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    }
    OperatingHoursValidator.requireValid(week);
    assertThat(OperatingHoursValidator.dayName(-1)).isEqualTo("Unknown");
  }

  @Test
  void completenessCountsVerifiedBankAndOpenDays() {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("flat", "1");
    address.put("area", "A");
    address.put("city", "C");
    address.put("state", "S");
    address.put("pincode", "560034");
    ProfileRecord full =
        new ProfileRecord(
            id,
            "PHM-0002",
            "Biz",
            "tag",
            "https://cdn/x.png",
            "+919876543210",
            "a@b.com",
            null,
            null,
            "PHARMACY",
            address,
            "ACTIVE",
            "GROWTH",
            "29AABPP1234F1ZZ",
            "AABPP1234F",
            "DL-1",
            "11223344556677",
            true,
            false,
            false,
            true,
            false,
            "Pharmacist",
            now,
            now);
    List<OperatingHoursRecord> hours = new ArrayList<>();
    for (int d = 0; d < 5; d++) {
      hours.add(
          new OperatingHoursRecord(
              Ids.newId(), id, d, LocalTime.of(9, 0), LocalTime.of(18, 0), false));
    }
    BankAccountRecord bank =
        new BankAccountRecord(
            Ids.newId(),
            id,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "VERIFIED",
            "RZP",
            now,
            now,
            now);
    ProfileCompleteness.Result result = ProfileCompleteness.calculate(full, hours, bank);
    assertThat(result.completenessPct()).isEqualTo(100);
  }
}
