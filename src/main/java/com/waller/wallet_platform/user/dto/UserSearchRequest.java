package com.waller.wallet_platform.user.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Query parameters of GET /api/users; every filter is optional, case-insensitive and matches a substring
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class UserSearchRequest {

    @Size(max = 100)
    private String username;

    @Size(max = 100)
    private String email;

    // Matches either the name or the email
    @Size(max = 100)
    private String keyword;

    // Unset sorts by id; id also breaks ties, so pages stay stable between requests
    private UserSortField sortField;

}
