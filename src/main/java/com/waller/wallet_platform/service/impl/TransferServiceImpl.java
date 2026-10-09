package com.waller.wallet_platform.service.impl;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.TransferMapper;
import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.entites.Transfer;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.TransferRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.service.TransferService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private static final String TRANSFER_NOT_FOUND = "Transfer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;
    private final AccountAccessChecker accountAccessChecker;

    @Override
    @Transactional(readOnly = true)
    public TransferDto getTransfer(Long id) {
        // Visible to the owner of either side; anyone else gets the same 404 as for a missing transfer
        Transfer transfer = transferRepository.findWithAccountsById(id)
                .filter(t -> accountAccessChecker.canAccess(t.getFromAccount())
                        || accountAccessChecker.canAccess(t.getToAccount()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSFER_NOT_FOUND));
        return transferMapper.toDto(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransferDto> getAccountTransfers(Long accountId, int page, int size) {
        if (!accountAccessChecker.canAccess(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return PageResponse.of(
                transferRepository.findByAccountId(accountId, PageRequest.of(page, size)),
                transferMapper::toDto);
    }

}
