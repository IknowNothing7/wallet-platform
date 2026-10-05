package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUserRequest;
import com.waller.wallet_platform.model.response.ApiResponse;

public interface AppUserService {

    ApiResponse<AppUser> createUser(AppUserRequest request); 

}
