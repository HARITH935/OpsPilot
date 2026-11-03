package com.opspilot.gateway.exception;

// Wrong username or password at POST /auth/token. One message for both, on purpose (see DemoUsers).
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Wrong username or password");
    }
}
