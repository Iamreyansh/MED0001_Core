package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Default FCM adapter — success unless token marked NOT_REGISTERED. */
public class StubFcmClient implements FcmClientPort {

  private final Set<String> notRegistered = ConcurrentHashMap.newKeySet();

  public void markNotRegistered(String token) {
    notRegistered.add(token);
  }

  public void clearNotRegistered() {
    notRegistered.clear();
  }

  @Override
  public PushResult send(PushRequest request) {
    if (request.token() != null && notRegistered.contains(request.token())) {
      return PushResult.fail("NOT_REGISTERED", "Requested entity was not found.");
    }
    return PushResult.ok("fcm_" + Ids.newId());
  }
}
