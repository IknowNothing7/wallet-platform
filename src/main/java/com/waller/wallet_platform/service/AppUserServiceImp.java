package com.waller.wallet_platform.service;

import org.springframework.stereotype.Service;

import com.waller.wallet_platform.mapper.AppUserMapper;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUserRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.repositories.AppUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j 
public class AppUserServiceImp implements AppUserService{

        private final AppUserRepository userRepository;
        private final AppUserMapper userMapper;

        @Override
        public ApiResponse<AppUser> createUser(AppUserRequest request) {

            AppUser user = userMapper.requestToEntity(request);
            userRepository.save(user);
            log.info("User was succesfully saved in the database" + user.getEmail());

            return ApiResponse.ok(user);
        }

}
