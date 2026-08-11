package com.nammamedmate.kernel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageObjectKeysTest {

  @Test
  void avatarAndExport_useTypedPrefixes() {
    assertThat(StorageObjectKeys.avatar("ramesh.png")).isEqualTo("avatars/ramesh.png");
    assertThat(StorageObjectKeys.export("customers-1.csv")).isEqualTo("exports/customers-1.csv");
    assertThat(StorageObjectKeys.report("gmv.csv")).isEqualTo("reports/gmv.csv");
  }

  @Test
  void key_rejectsBlankOrTraversal() {
    assertThatThrownBy(() -> StorageObjectKeys.key(" ", "a.png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StorageObjectKeys.key(null, "a.png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StorageObjectKeys.key("avatars", " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StorageObjectKeys.key("avatars", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StorageObjectKeys.key("avatars", "/"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StorageObjectKeys.key("avatars", "../x.png"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void key_stripsLeadingSlash() {
    assertThat(StorageObjectKeys.key("kyc", "/doc.pdf")).isEqualTo("kyc/doc.pdf");
  }
}
