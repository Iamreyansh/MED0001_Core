package com.nammamedmate.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportScheduleTimesCoverageTest {

  @Test
  void beforeRunTimeSameDayAndMondayBeforeAndMonthlyOnFirst() {
    // Thu Jul 23 2026 00:00 UTC = 05:30 IST — before 06:00 → same day daily
    Clock early = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
    assertThat(ReportScheduleTimes.nextRun("daily", early.instant(), early))
        .isEqualTo(Instant.parse("2026-07-23T00:30:00Z"));

    // Monday Jul 27 2026 00:00 UTC = 05:30 IST before 06:00 → same Monday
    Clock monEarly = Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);
    assertThat(ReportScheduleTimes.nextRun("WEEKLY", monEarly.instant(), monEarly))
        .isEqualTo(Instant.parse("2026-07-27T00:30:00Z"));

    // Monday after 06:00 IST → next week
    Clock monLate = Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC);
    assertThat(ReportScheduleTimes.nextRun("WEEKLY", monLate.instant(), monLate))
        .isEqualTo(Instant.parse("2026-08-03T00:30:00Z"));

    // Aug 1 2026 00:00 UTC = 05:30 IST on 1st before 06:00
    Clock first = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
    assertThat(ReportScheduleTimes.nextRun("MONTHLY", null, first))
        .isEqualTo(Instant.parse("2026-08-01T00:30:00Z"));

    assertThatThrownBy(() -> ReportScheduleTimes.nextRun(null, first.instant(), first))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
