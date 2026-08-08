package com.nammamedmate.api.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nammamedmate.messaging.SqsEventDispatcher;
import org.junit.jupiter.api.Test;

class OutboxDispatchSchedulerTest {

  @Test
  void dispatchOutboxDelegates() {
    SqsEventDispatcher dispatcher = mock(SqsEventDispatcher.class);
    new OutboxDispatchScheduler(dispatcher).dispatchOutbox();
    verify(dispatcher).dispatchOnce();
  }
}
