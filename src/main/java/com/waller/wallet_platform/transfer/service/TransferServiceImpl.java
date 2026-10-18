package com.waller.wallet_platform.transfer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.common.idempotency.IdempotencyService;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.security.AccountAccessChecker;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.Transfer;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private static final String TRANSFER_NOT_FOUND = "Transfer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String CREATE_ENDPOINT = "POST /transfer/create";

    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;
    private final AccountAccessChecker accountAccessChecker;
    private final TransferProcessingService transferProcessingService;
    private final IdempotencyService idempotencyService;

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

    // Booked synchronously: the response already says COMPLETED or FAILED, there is nothing to poll for
    @Override
    @Transactional
    public TransferDto createTransfer(TransferRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<TransferDto> previous = idempotencyService.findResponse(
                    idempotencyKey, CREATE_ENDPOINT, request, TransferDto.class);
            if (previous.isPresent()) {
                log.info("Transfer {} returned again for a repeated Idempotency-Key", previous.get().id());
                return previous.get();
            }
        }

        // Someone else's account answers like a missing one, so account ids can't be probed
        if (!accountAccessChecker.canAccess(request.getIdFromAccount())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account " + request.getIdFromAccount() + " not found");
        }
        TransferDto dto = transferProcessingService.book(request);
        if (idempotencyKey != null) {
            idempotencyService.save(idempotencyKey, CREATE_ENDPOINT, request, HttpStatus.CREATED, dto);
        }
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
