package com.nammamedmate.medicine_schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdherenceMathTest {

  @Test
  void pctUsesMultiply100() {
    // ponytail: story wrote -100; treat as ×100
    assertThat(AdherenceMath.pct(3, 4)).isEqualTo(75.0);
    assertThat(AdherenceMath.pct(0, 0)).isNull();
    assertThat(AdherenceMath.pct(4, 4)).isEqualTo(100.0);
    assertThat(AdherenceMath.pct(0, 4)).isEqualTo(0.0);
  }

  @Test
  void dayStatus() {
    assertThat(AdherenceMath.dayStatus(0, 0)).isEqualTo(DayAdherenceStatus.NO_DOSES);
    assertThat(AdherenceMath.dayStatus(4, 4)).isEqualTo(DayAdherenceStatus.PERFECT);
    assertThat(AdherenceMath.dayStatus(3, 4)).isEqualTo(DayAdherenceStatus.PARTIAL);
    assertThat(AdherenceMath.dayStatus(0, 4)).isEqualTo(DayAdherenceStatus.MISSED);
  }

  @Test
  void weekBand() {
    assertThat(WeekAdherenceBand.fromPct(85.0)).isEqualTo(WeekAdherenceBand.HIGH);
    assertThat(WeekAdherenceBand.fromPct(92.8)).isEqualTo(WeekAdherenceBand.HIGH);
    assertThat(WeekAdherenceBand.fromPct(84.0)).isEqualTo(WeekAdherenceBand.MEDIUM);
    assertThat(WeekAdherenceBand.fromPct(60.0)).isEqualTo(WeekAdherenceBand.MEDIUM);
    assertThat(WeekAdherenceBand.fromPct(59.9)).isEqualTo(WeekAdherenceBand.LOW);
    assertThat(WeekAdherenceBand.fromPct(null)).isEqualTo(WeekAdherenceBand.LOW);
  }
}
