package com.waller.wallet_platform.service;

import org.springframework.stereotype.Service;

import com.waller.wallet_platform.mapper.DepositMapper;
import com.waller.wallet_platform.model.entites.Deposit;
import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.repositories.DepositRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeposiServiceImpl  implements DepostiService{
    private final DepositRepository depositRepository;
    private final DepositMapper depositMapper;

    @Override
    public ApiResponse<Deposit> createDeposit(DepositRequest request) {

        var entity=depositMapper.requestToEntity(request);
        depositRepository.save(entity);
        return ApiResponse.ok(null);
    }
}
