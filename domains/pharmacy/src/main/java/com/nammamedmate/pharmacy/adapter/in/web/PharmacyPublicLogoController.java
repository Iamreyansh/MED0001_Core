package com.nammamedmate.pharmacy.adapter.in.web;

import com.nammamedmate.pharmacy.application.PharmacyLogoService;
import com.nammamedmate.pharmacy.application.PharmacyLogoService.PublicLogo;
import com.nammamedmate.pharmacy.application.PharmacyLogoService.PublicLogoRef;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PharmacyPublicLogoController {

  private final PharmacyLogoService logos;

  public PharmacyPublicLogoController(PharmacyLogoService logos) {
    this.logos = logos;
  }

  @GetMapping("/api/v1/public/pharmacy-logos/{fileName}")
  public ResponseEntity<byte[]> getLogo(@PathVariable String fileName) {
    PublicLogoRef parsed = PharmacyLogoService.parsePublicFileName(fileName);
    if (parsed == null) {
      return ResponseEntity.notFound().build();
    }
    PublicLogo logo = logos.readPublicLogo(parsed.pharmacyId(), parsed.ext());
    if (logo == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.contentType()))
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(logo.bytes());
  }
}
