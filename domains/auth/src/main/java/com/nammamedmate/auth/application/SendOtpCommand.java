package com.nammamedmate.auth.application;

public record SendOtpCommand(String phone, String deviceInfoJson, String clientIp) {}
