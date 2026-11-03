package com.opspilot.gateway.service;

import com.opspilot.gateway.config.DemoUsersProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Plain unit test: no Spring
class DemoUsersTest {

    private static final String PASSWORD = "a-long-test-password";

    private final DemoUsers users = new DemoUsers(new DemoUsersProperties(true, PASSWORD));

    @Test
    void eachDemoUserHasItsOwnRole() {
        assertThat(users.authenticate("viewer", PASSWORD)).contains(List.of("VIEWER"));
        assertThat(users.authenticate("engineer", PASSWORD)).contains(List.of("ENGINEER"));
        assertThat(users.authenticate("admin", PASSWORD)).contains(List.of("ADMIN"));
    }

    @Test
    void aWrongPasswordGetsNothing() {
        assertThat(users.authenticate("admin", "a-long-test-passwore")).isEmpty();   // one character off
        assertThat(users.authenticate("admin", "")).isEmpty();
    }

    @Test
    void anUnknownUserGetsNothingEvenWithThePassword() {
        assertThat(users.authenticate("root", PASSWORD)).isEmpty();
    }

    @Test
    void namesAreCaseSensitive() {
        assertThat(users.authenticate("Admin", PASSWORD)).isEmpty();
    }
}
