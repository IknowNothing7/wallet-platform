package com.waller.wallet_platform.user.service;

import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;

public interface AppUserService {

    AppUserDto createUser(AppUserRequest request);

}
