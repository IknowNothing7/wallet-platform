package com.waller.wallet_platform.transfer.service;

import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;

// Moves a transfer through the ledger: booked as COMPLETED or FAILED, and COMPLETED -> REVERSED
public interface TransferProcessingService {

    /**
     * Books a transfer in the caller's transaction; the caller must already have checked that the user may send from
     * the source account. Locks both accounts, checks them, the available balance and the limits, then writes the
     * TRANSFER entry. The transfer is stored in its final state either way: a business rejection (no funds, inactive
     * account, over a limit) is stored as FAILED and returned, not thrown, so it is committed. A request that can't
     * be a transfer at all (unknown account, wrong currency) is thrown as 4xx and stores nothing.
     */
    TransferDto book(TransferRequest request);

    /** Undoes a COMPLETED transfer with a REVERSAL entry in the opposite direction. Idempotent. */
    TransferDto reverse(Long transferId, String reason);
}
