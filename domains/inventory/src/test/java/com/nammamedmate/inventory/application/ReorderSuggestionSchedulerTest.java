package com.nammamedmate.inventory.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReorderSuggestionSchedulerTest {

  @Mock private PharmacyReorderService reorderService;

  @Test
  void refreshNightly_delegates() {
    new ReorderSuggestionScheduler(reorderService).refreshNightly();
    verify(reorderService).refreshAllPharmacies();
  }
}
