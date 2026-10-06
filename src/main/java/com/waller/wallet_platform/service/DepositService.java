package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.DepositDto;
import com.waller.wallet_platform.model.request.DepositRequest;

public interface DepositService {

    DepositDto createDeposit(DepositRequest request);
}
