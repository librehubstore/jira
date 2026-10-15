package com.fluxo.auth;

import com.fluxo.user.UserRepository;
import com.fluxo.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first administrator from FLUXO_ADMIN_USERNAME / FLUXO_ADMIN_PASSWORD when no admin exists. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final UserService userService;
    private final String username;
    private final String password;

    AdminBootstrap(UserRepository users, UserService userService,
                   @Value("${fluxo.admin.username:}") String username,
                   @Value("${fluxo.admin.password:}") String password) {
        this.users = users;
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank() || users.existsByAdminTrue()) return;
        userService.create(null, username, username, null, password, true, false);
        log.info("Administrateur initial '{}' créé", username);
    }
}
