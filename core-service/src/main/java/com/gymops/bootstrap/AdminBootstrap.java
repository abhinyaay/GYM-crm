package com.gymops.bootstrap;

import com.gymops.config.AppProperties;
import com.gymops.user.RoleName;
import com.gymops.user.RoleRepository;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first SUPER_ADMIN from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD when no
 * super admin exists yet. No credentials are hard-coded; in production the values come from the
 * secrets manager and can be removed after first start.
 */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsWithRole(RoleName.SUPER_ADMIN)) {
            return;
        }
        String email = props.bootstrap().adminEmail();
        String password = props.bootstrap().adminPassword();
        if (email == null || email.isBlank() || password == null || password.length() < 12) {
            log.warn("No SUPER_ADMIN exists and BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD (min 12 chars) "
                    + "are not set; skipping admin bootstrap");
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(password), "Platform Administrator", null,
                Set.of(roleRepository.findByName(RoleName.SUPER_ADMIN).orElseThrow()), Instant.now(clock)));
        log.info("Bootstrapped SUPER_ADMIN account {}", email);
    }
}
