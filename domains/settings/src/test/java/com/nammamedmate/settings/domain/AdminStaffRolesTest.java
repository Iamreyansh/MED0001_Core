package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class AdminStaffRolesTest {

  @Test
  void constantsAndConstructor() throws Exception {
    assertThat(AdminStaffRoles.ALL).contains(AdminStaffRoles.SUPER);
    assertThat(AdminStaffRoles.INVITEABLE).doesNotContain(AdminStaffRoles.SUPER);
    Constructor<AdminStaffRoles> ctor = AdminStaffRoles.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}
