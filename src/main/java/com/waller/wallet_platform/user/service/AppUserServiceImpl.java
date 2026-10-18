package com.waller.wallet_platform.user.service;

import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.common.util.ApiUtils;
import com.waller.wallet_platform.common.util.EmailUtils;
import com.waller.wallet_platform.common.util.PasswordUtils;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.user.repository.AppUserSpecifications;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserServiceImpl implements AppUserService {

    private static final String USER_NOT_FOUND = "User not found";
    private static final String AGGREGATE_TYPE = "user";
    private static final String USER_CREATED = "UserCreated";
    private static final String USER_ROLE_CHANGED = "UserRoleChanged";

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AppUserDto> getUsers(UserSearchRequest search, int page, int size) {
        return PageResponse.of(
                userRepository.findAll(AppUserSpecifications.matching(search), PageRequest.of(page, size, sort(search))),
                userMapper::toDto);
    }

    @Override
    @Transactional
    public AppUserDto changeRole(Long userId, UserRole role) {
        if (role == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The ADMIN role can not be granted");
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));
        if (user.getRole() == role) {
            return userMapper.toDto(user);
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The role of an admin can not be changed");
        }

        UserRole previous = user.getRole();
        user.setRole(role);
        // Flushed first so the event carries the new updatedAt
        AppUserDto dto = userMapper.toDto(userRepository.saveAndFlush(user));
        log.info("User {} changed from {} to {} by {}", user.getId(), previous, role, ApiUtils.getCurrentUsername());
        outboxEventService.record(AGGREGATE_TYPE, user.getId(), USER_ROLE_CHANGED, dto);
        return dto;
    }

    @Override
    @Transactional
    public AppUserDto bootstrapAdmin(String name, String email, String password) {
        String normalized = EmailUtils.normalize(email);
        Optional<AppUser> existing = userRepository.findByEmailIgnoreCase(normalized);
        if (existing.isPresent()) {
            if (existing.get().getRole() != UserRole.ADMIN) {
                log.warn("Admin bootstrap skipped: user {} already has the email but is {}, not ADMIN; "
                        + "pick another ADMIN_EMAIL", existing.get().getId(), existing.get().getRole());
            }
            return null;
        }
        if (PasswordUtils.isNotValidPassword(password)) {
            throw new IllegalStateException("ADMIN_PASSWORD is too weak: " + PasswordUtils.INVALID_PASSWORD_MESSAGE);
        }

        AppUser admin = AppUser.builder()
                .name(name)
                .email(normalized)
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.ADMIN)
                .build();
        AppUserDto dto = userMapper.toDto(userRepository.saveAndFlush(admin));
        log.info("Admin {} created by bootstrap", dto.id());
        // Same event as a registration; the DTO keeps the password hash out of it
        outboxEventService.record(AGGREGATE_TYPE, dto.id(), USER_CREATED, dto);
        return dto;
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
