package com.waller.wallet_platform.model.request.AppUser;

import com.waller.wallet_platform.model.enums.UserSortField;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class UserSearchRequest {

    private String username;
    private String email;

    private String keyword;
    private UserSortField sortField;

}
