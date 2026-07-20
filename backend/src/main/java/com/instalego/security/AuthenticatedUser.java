package com.instalego.security;

/** The authenticated principal attached to the security context by {@link JwtAuthFilter}. */
public record AuthenticatedUser(Long id, String email, String role) {
}
