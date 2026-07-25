package com.nammamedmate.auth.application.port.out;

public interface AuthSessionStore {

  AuthSessionRecord save(AuthSessionRecord session);
}
