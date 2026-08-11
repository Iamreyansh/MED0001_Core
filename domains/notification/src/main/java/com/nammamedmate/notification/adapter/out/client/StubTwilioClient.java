package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Twilio fallback stub. */
public class StubTwilioClient implements TwilioClientPort {

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
      return SendResult.fail("Twilio error");
    }
    return SendResult.ok("twilio_" + Ids.newId());
  }
}
