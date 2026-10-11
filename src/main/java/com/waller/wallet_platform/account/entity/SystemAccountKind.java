package com.waller.wallet_platform.account.entity;

// Platform-owned accounts; only service code posts to them and only reconciliation reads them
public enum SystemAccountKind {
    /** The platform's position against the real bank: |balance| is the money that should be there. */
    BANK_CLEARING,
    /** Platform revenue. */
    FEES,
    /** Money taken from payers but not yet given to merchants; equals the sum of active holds. */
    HOLDS
}
