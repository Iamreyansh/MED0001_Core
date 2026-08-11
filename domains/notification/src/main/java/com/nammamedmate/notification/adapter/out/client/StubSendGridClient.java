package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Configurable SendGrid stub — 503 / fail for tests. */
public class StubSendGridClient implements SendGridClientPort {

  private final AtomicBoolean serverError = new AtomicBoolean(false);
  private final AtomicBoolean fail = new AtomicBoolean(false);
  private final AtomicInteger sendCalls = new AtomicInteger();

  public void setServerError(boolean value) {
    serverError.set(value);
  }

  public void setFail(boolean value) {
    fail.set(value);
  }

  public int sendCallCount() {
    return sendCalls.get();
  }

  public void reset() {
    serverError.set(false);
    fail.set(false);
    sendCalls.set(0);
  }

  @Override
  public SendResult send(SendRequest request) {
    sendCalls.incrementAndGet();
    if (serverError.get()) {
      return SendResult.serverError("SendGrid 503");
    }
    if (fail.get()) {
      return SendResult.fail("SendGrid error");
    }
    return SendResult.ok("sg_" + Ids.newId());
  }
}
