package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.response.PageResponse;

public interface TransferService {

    TransferDto getTransfer(Long id);

    /** Incoming and outgoing transfers of the account, newest first. */
    PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size);

}
