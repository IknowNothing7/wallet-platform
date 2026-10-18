package com.waller.wallet_platform.ledger.controller;

import java.util.ArrayList;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.waller.wallet_platform.common.web.ApiResponse;
import com.waller.wallet_platform.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.service.JournalEntryService;

// Read-only on purpose: nothing over HTTP may write to the ledger, not even an admin. Money moves only as a step of a
// transfer, deposit or hold capture (LedgerService).
@RestController
@RequiredArgsConstructor
@RequestMapping("/journal-entry")
@PreAuthorize("hasRole('ADMIN')")
public class JournalEntryController {

    private final JournalEntryService journalEntryService;

    @GetMapping("/{id}")
    public ApiResponse<JournalEntryDto> getJournalEntry(@PathVariable("id") Long id) {
        return ApiResponse.ok(journalEntryService.getJournalEntry(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<JournalEntryDto>> getJournalEntries(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(journalEntryService.getJournalEntries(page, size));
    }

    // e.g. /journal-entry/reference?type=transfer&id=42
    @GetMapping("/reference")
    public ApiResponse<ArrayList<JournalEntryDto>> getJournalEntriesByReference(
            @RequestParam("type") String type,
            @RequestParam("id") Long id) {
        return ApiResponse.ok(new ArrayList<>(journalEntryService.getJournalEntriesByReference(type, id)));
    }

}
