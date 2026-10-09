package com.waller.wallet_platform.utils;

import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmailUtils {

    // Emails are stored lower-cased so Bob@x.com and bob@x.com are treated as one account
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

}
