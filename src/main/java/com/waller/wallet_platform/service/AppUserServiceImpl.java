package com.waller.wallet_platform.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.AppUserMapper;
import com.waller.wallet_platform.model.dto.AppUserDto;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUserRequest;
import com.waller.wallet_platform.repositories.AppUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserServiceImpl implements AppUserService {

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AppUserDto createUser(AppUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        AppUser user = userMapper.requestToEntity(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        AppUser saved = userRepository.save(user);
        log.info("User {} created", saved.getId());

        return userMapper.toDto(saved);
    }

}
