package com.olist.gateway.registration;

public record AccountProfile(
        String id,
        String email,
        String displayName,
        String avatarUrl) { }
