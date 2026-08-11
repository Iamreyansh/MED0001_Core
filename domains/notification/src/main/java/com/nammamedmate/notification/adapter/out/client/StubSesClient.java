package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Configurable SES stub. */
public class StubSesClient implements SesClientPort {

  private final AtomicBoolean fail = new AtomicBoolean(false);
  private final AtomicInteger sendCalls = new AtomicInteger();

  public void setFail(boolean value) {
    fail.set(value);
  }

  public int sendCallCount() {
    return sendCalls.get();
  }

  public void reset() {
    fail.set(false);
    sendCalls.set(0);
  }

  @Override
  public SendResult send(SendRequest request) {
    sendCalls.incrementAndGet();
    if (fail.get()) {
      return SendResult.fail("SES error");
    }
    return SendResult.ok("ses_" + Ids.newId());
  }
}
