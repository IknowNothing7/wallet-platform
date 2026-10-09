package com.waller.wallet_platform.controller;

import java.util.ArrayList;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.JournalEntryDto;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.service.JournalEntryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/journal-entry")
@PreAuthorize("hasRole('ADMIN')")
public class JournalEntryController {

    private final JournalEntryService journalEntryService;

    @GetMapping("/{id}")
    public ApiResponse<JournalEntryDto> getJournalEntry(@PathVariable Long id) {
        return ApiResponse.ok(journalEntryService.getJournalEntry(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<JournalEntryDto>> getJournalEntries(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(journalEntryService.getJournalEntries(page, size));
    }

    // e.g. /journal-entry/reference?type=transfer&id=42
    @GetMapping("/reference")
    public ApiResponse<ArrayList<JournalEntryDto>> getJournalEntriesByReference(
            @RequestParam String type,
            @RequestParam Long id) {
        return ApiResponse.ok(new ArrayList<>(journalEntryService.getJournalEntriesByReference(type, id)));
    }

}
