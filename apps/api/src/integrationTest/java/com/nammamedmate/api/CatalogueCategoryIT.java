package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CatalogueCategoryIT extends AbstractApiIT {

  @Autowired private TestRestTemplate rest;

  @Test
  void publicList_returnsSeededCategories() {
    ResponseEntity<Map> response =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/categories", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    assertThat(body.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> categories = (List<Map<String, Object>>) data.get("categories");
    assertThat(categories).hasSize(48);
    assertThat(categories.get(0)).containsKeys("category_id", "slug", "medicine_count");
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) body.get("meta");
    assertThat(meta).containsEntry("total", 48).containsKey("cached_at");
  }
}
