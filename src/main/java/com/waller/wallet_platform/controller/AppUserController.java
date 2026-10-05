package com.waller.wallet_platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.waller.wallet_platform.model.request.AppUserRequest;
import com.waller.wallet_platform.service.AppUserService;
import com.waller.wallet_platform.service.AppUserServiceImp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller 
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/user")
public class AppUserController {

    private final AppUserServiceImp userService;


    @PostMapping("/create")
    public void sendUserCreateRequest(@RequestBody AppUserRequest requestBody) {

        try {
        userService.createUser(requestBody);
        log.info("USER HAS BEEN CREATED");
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    // @GetMapping("/all")
    // public void getUsers() {

    // }

}
