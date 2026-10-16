package com.waller.wallet_platform.transfer.service;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.Transfer;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private static final String TRANSFER_NOT_FOUND = "Transfer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "transfer";
    private static final String TRANSFER_CREATED = "TransferCreated";

    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;
    private final AccountAccessChecker accountAccessChecker;
    private final AccountRepository accountRepository;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional(readOnly = true)
    public TransferDto getTransfer(Long id) {
        // Visible to the owner of either side; anyone else gets the same 404 as for a
        // missing transfer
        Transfer transfer = transferRepository.findWithAccountsById(id)
                .filter(t -> accountAccessChecker.canAccess(t.getFromAccount())
                        || accountAccessChecker.canAccess(t.getToAccount()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSFER_NOT_FOUND));
        return transferMapper.toDto(transfer);
    }

    @Override
    @Transactional
    public TransferDto createTransfer(TransferRequest request) {
        Account accountFrom = accountRepository.findUserAccountById(request.getIdFromAccount())
                .filter(t->accountAccessChecker.canAccess(request.getIdFromAccount()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Account " + request.getIdFromAccount() + " not found"));

        Account accountTo = accountRepository.findUserAccountById(request.getIdToAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Account " + request.getIdToAccount() + " not found"));

        if (!accountFrom.getCurrency().equals(accountTo.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer currency does not match  the target account currency " + accountTo.getCurrency());
        }

        if(Objects.equals(accountFrom.getId(), accountTo.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can not transfer to the same account");
        }

        Transfer transfer = transferRepository.saveAndFlush(transferMapper.requestToEntity(request));
        transfer.setFromAccount(accountFrom);
        transfer.setToAccount(accountTo);
        log.info("Transfer {} created for account {}", transfer.getId(), accountFrom.getId());

        TransferDto dto = transferMapper.toDto(transfer);
        outboxEventService.record(AGGREGATE_TYPE, transfer.getId(), TRANSFER_CREATED, dto);
        return dto;

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
