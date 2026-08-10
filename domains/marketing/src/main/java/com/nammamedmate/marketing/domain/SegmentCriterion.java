package com.nammamedmate.marketing.domain;

/** One custom-segment rule; CUSTOM segments AND all criteria together. */
public record SegmentCriterion(String field, String operator, Object value) {}
