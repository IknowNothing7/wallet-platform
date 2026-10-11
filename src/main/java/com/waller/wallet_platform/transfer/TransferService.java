package com.waller.wallet_platform.transfer;

import com.waller.wallet_platform.common.web.PageResponse;

public interface TransferService {

    TransferDto getTransfer(Long id);

    TransferDto createTransfer(TransferRequest request);

    /** Incoming and outgoing transfers of the account, newest first. */
    PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size);

}
