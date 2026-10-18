package com.waller.wallet_platform.user.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.entity.UserRole;

// Users sign up through /api/auth/register (always as USER); this is the admin side
public interface AppUserService {

    PageResponse<AppUserDto> getUsers(UserSearchRequest search, int page, int size);

    /**
     * Makes a user a MERCHANT or back a USER. ADMIN can be neither granted nor taken away here: admins come only from
     * {@link #bootstrapAdmin}, so no API call can create one or lock the last one out. Setting the current role
     * again returns the user unchanged. Takes effect on the user's next request, as roles are read per request.
     */
    AppUserDto changeRole(Long userId, UserRole role);

    /**
     * Creates an ADMIN with these credentials unless a user with the email already exists. An existing user is
     * left as it is, never promoted: whoever registered the email first would otherwise become admin.
     *
     * @return the created admin, or null when nothing was created
     */
    AppUserDto bootstrapAdmin(String name, String email, String password);

}
