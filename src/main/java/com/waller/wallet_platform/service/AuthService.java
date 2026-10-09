package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.request.AppUser.LoginRequest;
import com.waller.wallet_platform.model.request.AppUser.RegistrationRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.AuthResponse;

public interface AuthService {

    ApiResponse<AuthResponse> login(LoginRequest request);
    ApiResponse<AuthResponse> refreshAccessToken(String token);
    ApiResponse<AuthResponse> registerUser(RegistrationRequest request);
    /** Revokes the refresh token and, if given, the access token sent with the request. */
    void logout(String refreshToken, String accessToken);

}
