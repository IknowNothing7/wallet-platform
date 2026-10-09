package com.waller.wallet_platform.service.impl;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.AppUserMapper;
import com.waller.wallet_platform.model.dto.AppUserDto;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUser.AppUserRequest;
import com.waller.wallet_platform.repositories.AppUserRepository;

import com.waller.wallet_platform.service.AppUserService;
import com.waller.wallet_platform.utils.EmailUtils;
import com.waller.wallet_platform.utils.PasswordUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserServiceImpl implements AppUserService {

    private static final String EMAIL_TAKEN = "Email is already registered";

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AppUserDto createUser(AppUserRequest request) {
        if (PasswordUtils.isNotValidPassword(request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PasswordUtils.INVALID_PASSWORD_MESSAGE);
        }
        String email = EmailUtils.normalize(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, EMAIL_TAKEN);
        }

        AppUser user = userMapper.requestToEntity(request);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        AppUser saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same email won the race past the exists check
            throw new ResponseStatusException(HttpStatus.CONFLICT, EMAIL_TAKEN);
        }
        log.info("User {} created", saved.getId());

        return userMapper.toDto(saved);
    }

}
