package com.waller.wallet_platform.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.common.util.EmailUtils;
import com.waller.wallet_platform.common.util.PasswordUtils;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.user.repository.AppUserSpecifications;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserServiceImpl implements AppUserService {

    private static final String EMAIL_TAKEN = "Email is already registered";
    private static final String AGGREGATE_TYPE = "user";
    private static final String USER_CREATED = "UserCreated";

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final OutboxEventService outboxEventService;

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

        // The DTO, not the entity, so the password hash never reaches the event
        AppUserDto dto = userMapper.toDto(saved);
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), USER_CREATED, dto);
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AppUserDto> getUsers(UserSearchRequest search, int page, int size) {
        return PageResponse.of(
                userRepository.findAll(AppUserSpecifications.matching(search), PageRequest.of(page, size, sort(search))),
                userMapper::toDto);
    }

    // Id last, so users with the same name keep their order and pages stay stable between requests
    private static Sort sort(UserSearchRequest search) {
        Sort byId = Sort.by("id");
        if (search.getSortField() == null) {
            return byId;
        }
        return switch (search.getSortField()) {
            case NAME -> Sort.by("name").and(byId);
            case EMAIL -> Sort.by("email").and(byId);
        };
    }

}
