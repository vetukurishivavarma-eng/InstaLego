package com.instalego.config;

import com.instalego.model.User;
import com.instalego.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a single ADMIN user from ADMIN_EMAIL/ADMIN_PASSWORD on first boot, if one doesn't already
 * exist. Registration always creates USER accounts — this is the only way to get an ADMIN, so
 * there's no "first person to register becomes admin" privilege-escalation path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String adminEmail = System.getenv("ADMIN_EMAIL");
        String adminPassword = System.getenv("ADMIN_PASSWORD");

        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("ADMIN_EMAIL/ADMIN_PASSWORD not set — no admin account will be seeded. " +
                    "Set both env vars and restart to create one.");
            return;
        }

        if (userRepository.existsByEmailIgnoreCase(adminEmail)) {
            return;
        }

        User admin = new User();
        admin.setEmail(adminEmail.trim().toLowerCase());
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(User.Role.ADMIN);
        userRepository.save(admin);
        log.info("Seeded admin account: {}", admin.getEmail());
    }
}
