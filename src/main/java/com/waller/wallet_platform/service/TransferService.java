package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.entites.Transfer;
import com.waller.wallet_platform.model.request.TransferRequest;
import com.waller.wallet_platform.model.response.PageResponse;

public interface TransferService {

    TransferDto getTransfer(Long id);

    TransferDto createTransfer(TransferRequest request);

    /** Incoming and outgoing transfers of the account, newest first. */
    PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size);

}
