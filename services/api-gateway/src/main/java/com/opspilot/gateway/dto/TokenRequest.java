package com.opspilot.gateway.dto;

import jakarta.validation.constraints.NotBlank;

// Body of POST /auth/token: {"username":"admin","password":"..."}
public record TokenRequest(@NotBlank String username, @NotBlank String password) {

    // Records print all their fields: keep the password out of logs and error messages
    @Override
    public String toString() {
        return "TokenRequest[username=" + username + ", password=***]";
    }
}
