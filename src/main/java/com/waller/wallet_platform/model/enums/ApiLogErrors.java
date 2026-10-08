package com.waller.wallet_platform.model.enums;

import com.waller.wallet_platform.model.constants.ApiConstants;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ApiLogErrors {

    POST_NOT_FOUND_BY_ID("Post with ID: %s was not found"),
    COMMENT_NOT_FOUND_BY_ID("Comment with ID: %s was not found"),
    POST_NOT_FOUND_BY_USER_NAME("Post from user: %s was not found"),
    USER_NOT_FOUND_BY_ID("User with ID: %s was not found"),
    USER_NOT_FOUND_BY_EMAIL("User with email: %s was not found"),
    USER_ALREADY_EXIST("User with ID: %s already exist"),
    ROLE_NOT_FOUND_BY_NAME("Role with name: %s was not found"),
    TOKEN_NOT_FOUND("Token : %s was not found"),
    EMAIL_ALREADY_EXIST("Email: %s already exist"),
    POST_ALREADY_EXIST("Post with Title: %s already exists"),

    ERROR_DURING_JWT_PROCESSING("Error during JWT processing "),
    TOKEN_EXPIRED("Token expired"),
    UNEXPECTED_ERROR_OCCURRED("An unexpected error occurred."),

    AUTHENTICATION_FAILED_FOR_USER("Authentication failed for user: {}. "),
    INVALID_USER_OR_PASSWORD("Invalid email or password. Try again"),
    INVALID_USER_REGISTRATION_STATUS("Invalid user registration status: %s."),


    MISMATCH_PASSWORDS("Password does not match"),
    INVALID_PASSWORD("Invalid password. It must have: "
            + "length at least " + ApiConstants.REQUIRED_MIN_PASSWORD_LENGTH + ", including "
            + ApiConstants.REQUIRED_MIN_LETTERS_NUMBER_EVERY_CASE_IN_PASSWORD + " letter(s) in upper and lower cases, "
            + ApiConstants.REQUIRED_MIN_CHARACTERS_NUMBER_IN_PASSWORD + " character(s), "
            + ApiConstants.REQUIRED_MIN_DIGITS_NUMBER_IN_PASSWORD + " digit(s). "),
    HAVE_NO_ACCESS("You have no permission to do that");

    private final String message;

    public String getMessage(Object... args) {
        return String.format(message, args);
    }
}
