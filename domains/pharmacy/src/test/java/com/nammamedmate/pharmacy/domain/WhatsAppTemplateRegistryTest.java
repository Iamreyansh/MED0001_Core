package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhatsAppTemplateRegistryTest {

  @Test
  void approvedTemplates() {
    assertThat(WhatsAppTemplateRegistry.isApproved("PHARMACY_GENERAL_NOTICE")).isTrue();
    assertThat(WhatsAppTemplateRegistry.isApproved("UNKNOWN")).isFalse();
    assertThat(WhatsAppTemplateRegistry.isApproved(null)).isFalse();
    assertThat(WhatsAppTemplateRegistry.APPROVED).hasSize(4);
  }
}
