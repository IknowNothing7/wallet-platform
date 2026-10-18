package com.waller.wallet_platform.user.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.user.service.AppUserService;

/**
 * The only way to get an ADMIN: on startup, creates one from ADMIN_EMAIL / ADMIN_PASSWORD (admin.bootstrap.*) if no
 * user has that email yet. That admin then makes merchants through PUT /api/users/{id}/role. Without ADMIN_EMAIL it does
 * nothing. A weak password fails startup rather than leaving the platform without an admin.
 *
 * <p>Several instances starting at once are safe: the unique email lets only one insert win, and the others fail
 * startup on that one boot and find the admin on restart.
 */
@Component
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final AppUserService userService;
    private final String email;
    private final String password;
    private final String name;

    public AdminBootstrap(
            AppUserService userService,
            @Value("${admin.bootstrap.email:}") String email,
            @Value("${admin.bootstrap.password:}") String password,
            @Value("${admin.bootstrap.name:Admin}") String name) {
        this.userService = userService;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            log.debug("ADMIN_EMAIL not set, no admin bootstrap");
            return;
        }
        if (password.isBlank()) {
            throw new IllegalStateException("ADMIN_EMAIL is set but ADMIN_PASSWORD is not");
        }
        userService.bootstrapAdmin(name, email, password);
    }
}
