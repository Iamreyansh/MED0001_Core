package com.nammamedmate.crm.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountHealthSchedulerTest {

  @Mock AccountHealthService health;

  @Test
  void runDelegates() {
    new AccountHealthScheduler(health).run();
    verify(health).recomputeAll();
  }
}
