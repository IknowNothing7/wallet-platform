package com.waller.wallet_platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.repositories.AccountRepository;

import lombok.RequiredArgsConstructor;

// Decides whether the current user may see an account's data: its owner or an admin
@Component
@RequiredArgsConstructor
public class AccountAccessChecker {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final AccountRepository accountRepository;

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /** False for unknown accounts too, so callers can answer both cases with the same 404. */
    @Transactional(readOnly = true)
    public boolean canAccess(Long accountId) {
        if (isAdmin()) {
            return accountRepository.existsById(accountId);
        }
        return accountRepository.findById(accountId)
                .map(this::isOwner)
                .orElse(false);
    }

    /** For an account already loaded (e.g. through a deposit or transfer); its user must be fetchable. */
    public boolean canAccess(Account account) {
        return isAdmin() || isOwner(account);
    }

    // The principal's name is the user's email (see AppUserDetailsService)
    private boolean isOwner(Account account) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && account.getUser().getEmail().equalsIgnoreCase(authentication.getName());
    }

}
