package com.waller.wallet_platform.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.service.DeposiServiceImpl;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@RestController 
@RequestMapping("/deposit")
@Slf4j 
@RequiredArgsConstructor 
@Getter 
@Setter 
public class DepositController {
    
    private final DeposiServiceImpl depositService;


    @PostMapping("/create")
    public void createDeposit( @RequestBody DepositRequest request){
            depositService.createDeposit(request);
    }

}
