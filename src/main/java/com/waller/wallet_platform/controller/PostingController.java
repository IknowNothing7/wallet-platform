package com.waller.wallet_platform.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.service.PostingService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/posting")
public class PostingController {

    private final PostingService postingService;

    @GetMapping("/{id}")
    public ApiResponse<PostingDto> getPosting(@PathVariable("id") Long id) {
        return ApiResponse.ok(postingService.getPosting(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<PostingDto>> getAccountPostings(
            @PathVariable("accountId") Long accountId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(postingService.getAccountPostings(accountId, page, size));
    }

    @GetMapping("/journal-entry/{journalEntryId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ArrayList<PostingDto>> getJournalEntryPostings(@PathVariable("journalEntryId") Long journalEntryId) {
        List<PostingDto> postings = postingService.getJournalEntryPostings(journalEntryId);
        return ApiResponse.ok(new ArrayList<>(postings));
    }

}
