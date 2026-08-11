package com.nammamedmate.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportScheduleTimesTest {

  @Test
  void nextRunsForCadences() {
    // Friday 2026-07-24 01:30 UTC = 07:00 IST — after 06:00 so daily → tomorrow
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:30:00Z"), ZoneOffset.UTC);
    Instant daily = ReportScheduleTimes.nextRun("DAILY", clock.instant(), clock);
    assertThat(daily).isEqualTo(Instant.parse("2026-07-25T00:30:00Z")); // Sat 06:00 IST

    Instant weekly = ReportScheduleTimes.nextRun("WEEKLY", clock.instant(), clock);
    // next Monday after Friday → 2026-07-27 06:00 IST = 00:30Z
    assertThat(weekly).isEqualTo(Instant.parse("2026-07-27T00:30:00Z"));

    Instant monthly = ReportScheduleTimes.nextRun("MONTHLY", clock.instant(), clock);
    assertThat(monthly).isEqualTo(Instant.parse("2026-08-01T00:30:00Z")); // Aug 1 06:00 IST

    assertThatThrownBy(() -> ReportScheduleTimes.nextRun("ON_DEMAND", clock.instant(), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
