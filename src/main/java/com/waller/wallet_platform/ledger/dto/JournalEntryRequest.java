package com.waller.wallet_platform.ledger.dto;

import java.io.Serializable;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;

// What a domain service asks LedgerService to post; built in code, never read from an HTTP body
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class JournalEntryRequest implements Serializable {

    @NotNull(message = "entryType can not be null")
    private JournalEntryType entryType;

    @Size(max = 255)
    private String description;

    @Size(max = 50)
    private String referenceType;

    private Long referenceId;

    // Debits must equal credits per currency; checked in the service
    @NotNull(message = "postings can not be null")
    @Size(min = 2, message = "a journal entry needs at least two postings")
    private List<@Valid PostingRequest> postings;

}
