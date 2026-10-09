package com.waller.wallet_platform.security;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.repositories.AppUserRepository;
import com.waller.wallet_platform.utils.EmailUtils;

import lombok.RequiredArgsConstructor;

// Loads users by email (the JWT subject); defining this bean also disables Spring Boot's in-memory default user
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AppUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        AppUser user = userRepository.findByEmailIgnoreCase(EmailUtils.normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.getRole().name())))
                .build();
    }

}
