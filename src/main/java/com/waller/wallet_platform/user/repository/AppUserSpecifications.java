package com.waller.wallet_platform.user.repository;

import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.entity.AppUser;

// Filters for GET /user. Blank filters are ignored; LIKE wildcards in the input match literally.
public final class AppUserSpecifications {

    private static final char ESCAPE = '\\';

    private AppUserSpecifications() {
    }

    public static Specification<AppUser> matching(UserSearchRequest search) {
        Specification<AppUser> spec = Specification.unrestricted();
        if (isPresent(search.getUsername())) {
            spec = spec.and(contains("name", search.getUsername()));
        }
        if (isPresent(search.getEmail())) {
            spec = spec.and(contains("email", search.getEmail()));
        }
        if (isPresent(search.getKeyword())) {
            spec = spec.and(contains("name", search.getKeyword()).or(contains("email", search.getKeyword())));
        }
        return spec;
    }

    private static Specification<AppUser> contains(String attribute, String value) {
        String pattern = "%" + escape(value.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get(attribute)), pattern, ESCAPE);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
