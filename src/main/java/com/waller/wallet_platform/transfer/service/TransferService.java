package com.waller.wallet_platform.transfer.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;

public interface TransferService {

    TransferDto getTransfer(Long id);

    /**
     * Creates and books the transfer in one transaction; the result is COMPLETED or FAILED. With an idempotency key,
     * a retry returns the transfer created by the first request; the key may be null.
     */
    TransferDto createTransfer(TransferRequest request, String idempotencyKey);

    /** Incoming and outgoing transfers of the account, newest first. */
    PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size);

}
