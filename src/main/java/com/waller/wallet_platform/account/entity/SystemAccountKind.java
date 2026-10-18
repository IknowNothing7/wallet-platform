package com.waller.wallet_platform.account.entity;

// Platform-owned accounts; only service code posts to them and only reconciliation reads them
public enum SystemAccountKind {
    /** The platform's position against the real bank: |balance| is the money that should be there. */
    BANK_CLEARING,
    /** Platform revenue. */
    FEES
    // No HOLDS account: a hold is a reservation (the sum of ACTIVE holds is subtracted from the available balance),
    // and nothing is posted until it is captured
}
