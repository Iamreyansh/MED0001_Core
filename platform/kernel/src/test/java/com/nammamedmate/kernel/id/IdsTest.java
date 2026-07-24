package com.nammamedmate.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void newIdAndParse() {
    UUID id = Ids.newId();
    assertThat(Ids.parse(id.toString())).isEqualTo(id);
    assertThat(Ids.parse("  " + id + "  ")).isEqualTo(id);
  }

  @Test
  void parseRejectsBlank() {
    assertThatThrownBy(() -> Ids.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ids.parse("  ")).isInstanceOf(IllegalArgumentException.class);
  }
}
