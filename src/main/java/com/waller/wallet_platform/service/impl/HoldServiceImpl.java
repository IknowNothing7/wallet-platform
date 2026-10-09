package com.waller.wallet_platform.service.impl;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.HoldMapper;
import com.waller.wallet_platform.model.dto.HoldDto;
import com.waller.wallet_platform.model.entites.Hold;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.HoldRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.service.HoldService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HoldServiceImpl implements HoldService {

    private static final String HOLD_NOT_FOUND = "Hold not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final HoldRepository holdRepository;
    private final HoldMapper holdMapper;
    private final AccountAccessChecker accountAccessChecker;

    @Override
    @Transactional(readOnly = true)
    public HoldDto getHold(Long id) {
        // Someone else's hold looks the same as a missing one, so ids can't be probed
        Hold hold = holdRepository.findWithAccountById(id)
                .filter(h -> accountAccessChecker.canAccess(h.getAccount()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, HOLD_NOT_FOUND));
        return holdMapper.toDto(hold);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HoldDto> getAccountHolds(Long accountId, int page, int size) {
        if (!accountAccessChecker.canAccess(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return PageResponse.of(
                holdRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size)),
                holdMapper::toDto);
    }

}
