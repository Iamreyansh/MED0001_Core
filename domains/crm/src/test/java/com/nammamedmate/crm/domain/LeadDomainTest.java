package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class LeadDomainTest {

  @Test
  void stageSourceLostReason() {
    assertThat(LeadStage.requireValid("demo")).isEqualTo(LeadStage.DEMO);
    assertThat(LeadStage.isOpen(LeadStage.NEW)).isTrue();
    assertThat(LeadStage.isOpen(LeadStage.WON)).isFalse();
    assertThat(LeadStage.defaultWinProbability(LeadStage.TRIAL)).isEqualTo(60);
    assertThat(LeadStage.defaultWinProbability(LeadStage.WON)).isEqualTo(100);
    assertThat(LeadStage.nextOpen(LeadStage.NEW)).isEqualTo(LeadStage.CONTACTED);
    assertThat(LeadStage.nextOpen(LeadStage.TRIAL)).isNull();
    assertThatThrownBy(() -> LeadStage.requireValid("X")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LeadStage.requireValid(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LeadStage.requireValid("  ")).isInstanceOf(AppException.class);

    assertThat(LeadSource.requireValid("marketplace")).isEqualTo(LeadSource.MARKETPLACE);
    assertThatThrownBy(() -> LeadSource.requireValid("X")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LeadSource.requireValid(" ")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LeadSource.requireValid(null)).isInstanceOf(AppException.class);

    assertThat(LostReason.requireValid("price")).isEqualTo(LostReason.PRICE);
    assertThatThrownBy(() -> LostReason.requireValid("X")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LostReason.requireValid(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> LostReason.requireValid("")).isInstanceOf(AppException.class);

    assertThat(LeadStage.defaultWinProbability("UNKNOWN")).isEqualTo(0);
  }
}
