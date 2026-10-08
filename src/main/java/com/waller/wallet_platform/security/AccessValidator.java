package com.waller.wallet_platform.security;

import org.springframework.stereotype.Component;

import com.waller.wallet_platform.exception.DataExistException;
import com.waller.wallet_platform.exception.InvalidDataException;
import com.waller.wallet_platform.model.enums.ApiLogErrors;
import com.waller.wallet_platform.repositories.AppUserRepository;
import com.waller.wallet_platform.utils.ApiUtils;
import com.waller.wallet_platform.utils.PasswordUtils;

import lombok.RequiredArgsConstructor;

@Component 
@RequiredArgsConstructor 
public class AccessValidator {

    private final AppUserRepository userRepository;
    private final ApiUtils apiUtils;

        public void validateNewUser(String username, String email, String password, String confirmPassword) {

        userRepository.findByUsername(username).ifPresent(existingUser -> {
            throw new DataExistException(ApiLogErrors.USER_ALREADY_EXIST.getMessage(username));
        });
        userRepository.findByEmail(email).ifPresent(existingEmail -> {
            throw new DataExistException(ApiLogErrors.EMAIL_ALREADY_EXIST.getMessage(email));
        });

        if (!password.equals(confirmPassword)) {
            throw new InvalidDataException(ApiLogErrors.MISMATCH_PASSWORDS.getMessage());
        }

        if (PasswordUtils.isNotValidPassword(password)) {
            throw new InvalidDataException(ApiLogErrors.INVALID_PASSWORD.getMessage());
        }
    }

}
