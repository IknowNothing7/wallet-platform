package com.waller.wallet_platform.deposit.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.deposit.dto.DepositDto;
import com.waller.wallet_platform.deposit.dto.DepositRequest;

public interface DepositService {

    DepositDto createDeposit(DepositRequest request);

    DepositDto getDeposit(Long id);

    PageResponse<DepositDto> getAccountDeposits(Long accountId, int page, int size);
}
