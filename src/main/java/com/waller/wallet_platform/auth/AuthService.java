package com.waller.wallet_platform.auth;

import com.waller.wallet_platform.common.web.ApiResponse;

public interface AuthService {

    ApiResponse<AuthResponse> login(LoginRequest request);
    ApiResponse<AuthResponse> refreshAccessToken(String token);
    ApiResponse<AuthResponse> registerUser(RegistrationRequest request);
    /** Revokes the refresh token and, if given, the access token sent with the request. */
    void logout(String refreshToken, String accessToken);

}
