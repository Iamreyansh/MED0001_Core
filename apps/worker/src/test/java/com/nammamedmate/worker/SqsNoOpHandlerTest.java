package com.nammamedmate.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SqsNoOpHandlerTest {

  @Test
  void delegatesToRouter() {
    DomainEventRouter router = mock(DomainEventRouter.class);
    SqsNoOpHandler handler = new SqsNoOpHandler(router);
    handler.handle("{\"type\":\"order.created\"}");
    verify(router).handle("{\"type\":\"order.created\"}");
  }
}
