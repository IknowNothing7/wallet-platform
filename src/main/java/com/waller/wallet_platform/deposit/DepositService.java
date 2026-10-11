package com.waller.wallet_platform.deposit;

import com.waller.wallet_platform.common.web.PageResponse;

public interface DepositService {

    DepositDto createDeposit(DepositRequest request);

    DepositDto getDeposit(Long id);

    PageResponse<DepositDto> getAccountDeposits(Long accountId, int page, int size);
}
