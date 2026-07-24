package com.nammamedmate.worker;

import org.junit.jupiter.api.Test;

class SqsNoOpHandlerTest {

  @Test
  void handlesNullBlankAndPayload() {
    SqsNoOpHandler handler = new SqsNoOpHandler();
    handler.handle(null);
    handler.handle("  ");
    handler.handle("{\"type\":\"order.created\"}");
  }
}
