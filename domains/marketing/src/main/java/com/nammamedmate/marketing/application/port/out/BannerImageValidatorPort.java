package com.nammamedmate.marketing.application.port.out;

/** Validates CDN image URL is reachable JPG/PNG under 2 MB. */
public interface BannerImageValidatorPort {

  void validate(String imageUrl);
}
