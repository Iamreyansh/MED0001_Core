package com.nammamedmate.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountingDomainTest {

  @Test
  void constantsAndValidators() {
    assertThat(AccountingSystems.isValid("TALLY")).isTrue();
    assertThat(AccountingSystems.isValid("X")).isFalse();
    assertThat(AccountingSyncTypes.isValid("SALES")).isTrue();
    assertThat(AccountingSyncTypes.isTallyExportable("EXPENSES")).isFalse();
    assertThat(AccountingSyncStatuses.isActive("QUEUED")).isTrue();
    assertThat(AccountingSyncStatuses.isActive("COMPLETED")).isFalse();
    assertThat(AccountingSyncFrequencies.isValid("DAILY")).isTrue();
    assertThat(AccountingSyncFrequencies.isValid("X")).isFalse();
    assertThat(AccountingApiKeyStatuses.CONNECTED).isEqualTo("CONNECTED");
    assertThat(AccountingTriggeredBy.MANUAL).isEqualTo("MANUAL");
  }

  @Test
  void nextSyncCalculatorDailyAndWeekly() {
    // Monday 2026-07-20 01:00 IST = 2026-07-19 19:30 UTC → next daily same day 02:00 IST
    Clock beforeDaily = Clock.fixed(Instant.parse("2026-07-19T19:30:00Z"), ZoneOffset.UTC);
    Instant daily = NextSyncAtCalculator.next("DAILY", beforeDaily);
    assertThat(daily.atZone(NextSyncAtCalculator.IST).toLocalTime().getHour()).isEqualTo(2);

    // Monday after 02:00 IST → next Monday
    Clock mondayAfternoon = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);
    Instant weekly = NextSyncAtCalculator.next("WEEKLY", mondayAfternoon);
    assertThat(weekly.atZone(NextSyncAtCalculator.IST).getDayOfWeek().getValue()).isEqualTo(1);
  }

  @Test
  void tallyXmlEscapesAndAmounts() {
    AccountingVoucher v =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "A&B<1>",
            LocalDate.of(2026, 7, 1),
            "Party \"X\"",
            null,
            1050,
            50,
            1050);
    String xml = TallyXmlBuilder.buildSales(List.of(v));
    assertThat(xml).contains("A&amp;B&lt;1&gt;").contains("10.50").contains("<ENVELOPE>");
    AccountingVoucher nullParty =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "N",
            LocalDate.of(2026, 7, 1),
            null,
            null,
            -105,
            0,
            -105);
    assertThat(TallyXmlBuilder.buildSales(List.of(nullParty)))
        .contains("<PARTYLEDGERNAME></PARTYLEDGERNAME>");
    assertThat(AccountingSyncTypes.isTallyExportable("SALES")).isTrue();
    // Monday before 02:00 IST → same Monday
    Clock monEarly =
        Clock.fixed(Instant.parse("2026-07-19T20:00:00Z"), ZoneOffset.UTC); // 01:30 IST Mon
    Instant weekly = NextSyncAtCalculator.next("WEEKLY", monEarly);
    assertThat(weekly.atZone(NextSyncAtCalculator.IST).toLocalDate())
        .isEqualTo(LocalDate.of(2026, 7, 20));
  }

  @Test
  void syncJobCopiesErrors() {
    AccountingSyncJob job =
        new AccountingSyncJob(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TALLY",
            "SALES",
            LocalDate.now(),
            LocalDate.now(),
            "QUEUED",
            0,
            0,
            0,
            null,
            "MANUAL",
            Instant.now(),
            null,
            null);
    assertThat(job.errors()).isEmpty();
  }
}
