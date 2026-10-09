package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.DepositDto;
import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.model.response.PageResponse;

public interface DepositService {

    DepositDto createDeposit(DepositRequest request);

    DepositDto getDeposit(Long id);

    PageResponse<DepositDto> getAccountDeposits(Long accountId, int page, int size);
}
