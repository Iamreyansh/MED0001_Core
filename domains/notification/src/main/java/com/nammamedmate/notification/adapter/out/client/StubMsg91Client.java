package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Configurable MSG91 stub — timeout / fail / DND for tests. */
public class StubMsg91Client implements Msg91ClientPort {

  private final AtomicBoolean timeout = new AtomicBoolean(false);
  private final AtomicBoolean fail = new AtomicBoolean(false);
  private final AtomicInteger sendCalls = new AtomicInteger();
  private final AtomicInteger dndCalls = new AtomicInteger();
  private final Set<String> dndPhones = ConcurrentHashMap.newKeySet();

  public void setTimeout(boolean value) {
    timeout.set(value);
  }

  public void setFail(boolean value) {
    fail.set(value);
  }

  public void markDnd(String phone) {
    dndPhones.add(phone);
  }

  public void clearDnd() {
    dndPhones.clear();
  }

  public int sendCallCount() {
    return sendCalls.get();
  }

  public int dndCallCount() {
    return dndCalls.get();
  }

  public void reset() {
    timeout.set(false);
    fail.set(false);
    sendCalls.set(0);
    dndCalls.set(0);
    dndPhones.clear();
  }

  @Override
  public boolean isOnDnd(String toPhone) {
    dndCalls.incrementAndGet();
    return toPhone != null && dndPhones.contains(toPhone);
  }

  @Override
  public SendResult send(SendRequest request) {
    sendCalls.incrementAndGet();
    if (timeout.get()) {
      return SendResult.timeout();
    }
    if (fail.get()) {
      return SendResult.fail("MSG91 error");
    }
    return SendResult.ok("msg91_" + Ids.newId());
  }
}
