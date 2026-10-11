package com.waller.wallet_platform.transfer.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;

public interface TransferService {

    TransferDto getTransfer(Long id);

    TransferDto createTransfer(TransferRequest request);

    /** Incoming and outgoing transfers of the account, newest first. */
    PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size);

}
