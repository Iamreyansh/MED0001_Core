package com.nammamedmate.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class EntityConstructorsTest {

  @Test
  void protectedNoArgConstructorsExistForJpa() throws Exception {
    assertThat(newInstance(OtpSessionEntity.class)).isNotNull();
    assertThat(newInstance(CustomerEntity.class)).isNotNull();
    assertThat(newInstance(AuthSessionEntity.class)).isNotNull();
    assertThat(newInstance(PharmacyEntity.class)).isNotNull();
    assertThat(newInstance(PharmacyStaffEntity.class)).isNotNull();
    assertThat(newInstance(PharmacyAssignmentEntity.class)).isNotNull();
    assertThat(newInstance(LoginAuditEntity.class)).isNotNull();
  }

  private static Object newInstance(Class<?> type) throws Exception {
    Constructor<?> ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }
}
