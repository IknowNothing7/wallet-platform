package com.waller.wallet_platform.ledger.service;

import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;

/**
 * The only way money moves. Called by the domain services (transfer, deposit, hold) as one step of their own
 * transaction, never from a controller: there is no HTTP endpoint that writes to the ledger.
 *
 * <p>An entry is written once, with all of its postings, and never changed afterwards (the tables are UPDATE/DELETE
 * revoked, see V11). A correction is a new entry, e.g. a REVERSAL.
 */
public interface LedgerService {

    /**
     * Writes the entry and its postings and applies them to the account balances. The postings must balance per
     * currency, and a user account must not go negative: if one would, nothing is written and 409 is thrown.
     * There can be only one entry per (referenceType, referenceId, entryType).
     */
    JournalEntryDto post(JournalEntryRequest entry);

}
