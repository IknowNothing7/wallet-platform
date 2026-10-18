package com.waller.wallet_platform.user.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.dto.UserSearchRequest;

public interface AppUserService {

    AppUserDto createUser(AppUserRequest request);

    PageResponse<AppUserDto> getUsers(UserSearchRequest search, int page, int size);

}
