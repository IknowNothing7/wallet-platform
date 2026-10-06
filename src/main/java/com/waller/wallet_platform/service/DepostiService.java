package com.waller.wallet_platform.service;

import org.springframework.stereotype.Service;

import com.waller.wallet_platform.model.entites.Deposit;
import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.model.response.ApiResponse;

@Service 
public interface DepostiService {

    ApiResponse<Deposit>createDeposit(DepositRequest request);
}
