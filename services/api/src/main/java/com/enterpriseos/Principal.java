package com.enterpriseos;

public record Principal(String userId, String tenantId, String name, String email, String role) { }
