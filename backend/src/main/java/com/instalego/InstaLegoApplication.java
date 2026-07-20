package com.instalego;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

// UserDetailsServiceAutoConfiguration is excluded because auth is handled entirely by our own
// JwtAuthFilter + AuthService (manual BCrypt check) — without this, Spring Boot generates and
// logs a random default-user password on every boot that nothing in our filter chain ever uses.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
public class InstaLegoApplication {

    public static void main(String[] args) {
        SpringApplication.run(InstaLegoApplication.class, args);
    }
}
