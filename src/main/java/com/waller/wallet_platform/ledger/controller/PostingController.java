package com.waller.wallet_platform.ledger.controller;

import java.util.ArrayList;
import java.util.List;

import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.waller.wallet_platform.common.web.ApiResponse;
import com.waller.wallet_platform.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.ledger.service.PostingService;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/posting")
@Slf4j
public class PostingController {

    private final PostingService postingService;

    // Adds a balanced set of postings (debits == credits) to an existing journal entry
    @PostMapping("/journal-entry/{journalEntryId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ArrayList<PostingDto>> createPostings(
            @PathVariable("journalEntryId") Long journalEntryId,
            @RequestBody List<@Valid PostingRequest> requests) {
        log.info("Endpoint {}", ApiUtils.getMethodName());
        return ApiResponse.ok(new ArrayList<>(postingService.createPostings(journalEntryId, requests)));
    }

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
