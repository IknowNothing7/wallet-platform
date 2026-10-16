package com.waller.wallet_platform.transfer.service;

import com.waller.wallet_platform.transfer.dto.TransferDto;

// Moves a transfer through the ledger: PENDING -> COMPLETED | FAILED, and COMPLETED -> REVERSED
public interface TransferProcessingService {

    /**
     * Books a PENDING transfer: checks both accounts and the available balance, then writes the TRANSFER entry.
     * A business rejection (no funds, inactive account) ends as FAILED, not as an exception, so it is committed.
     * Idempotent: a transfer that is no longer PENDING is returned unchanged.
     */
    TransferDto process(Long transferId);

    /** Undoes a COMPLETED transfer with a REVERSAL entry in the opposite direction. Idempotent. */
    TransferDto reverse(Long transferId, String reason);
}
