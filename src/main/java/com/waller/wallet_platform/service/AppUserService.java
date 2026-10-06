package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.AppUserDto;
import com.waller.wallet_platform.model.request.AppUserRequest;

public interface AppUserService {

    AppUserDto createUser(AppUserRequest request);

}
