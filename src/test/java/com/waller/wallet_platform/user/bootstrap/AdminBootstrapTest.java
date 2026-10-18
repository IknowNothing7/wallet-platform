package com.waller.wallet_platform.user.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.waller.wallet_platform.user.service.AppUserService;

class AdminBootstrapTest {

    private final AppUserService userService = mock(AppUserService.class);

    @Test
    void withoutAdminEmail_doesNothing() {
        new AdminBootstrap(userService, "", "", "Admin").run(null);

        verifyNoInteractions(userService);
    }

    @Test
    void withCredentials_bootstrapsTheAdmin() {
        new AdminBootstrap(userService, "root@email.com", "Str0ng!Password", "Root").run(null);

        verify(userService).bootstrapAdmin("Root", "root@email.com", "Str0ng!Password");
    }

    // Better to fail startup than to come up without the admin that was asked for
    @Test
    void emailWithoutPassword_failsStartup() {
        AdminBootstrap bootstrap = new AdminBootstrap(userService, "root@email.com", "", "Root");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
        verifyNoInteractions(userService);
    }
}
