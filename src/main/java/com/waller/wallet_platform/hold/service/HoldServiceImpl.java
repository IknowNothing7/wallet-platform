package com.waller.wallet_platform.hold.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.hold.mapper.HoldMapper;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;
import com.waller.wallet_platform.hold.entity.Hold;

@Service
@RequiredArgsConstructor
public class HoldServiceImpl implements HoldService {

    private static final String HOLD_NOT_FOUND = "Hold not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final HoldRepository holdRepository;
    private final AccountRepository accountRepository;
    private final HoldMapper holdMapper;
    private final AccountAccessChecker accountAccessChecker;

    // Merchant-only (enforced in the controller): the account belongs to the customer, not the caller
    @Override
    @Transactional
    public HoldDto createHold(HoldRequest request) {
        Account account = accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }

        if (!account.getCurrency().equals(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hold currency does not match account currency " + account.getCurrency());
        }
        // TODO: check available balance (balance minus active holds) once balances are maintained

        Hold hold = new Hold();
        hold.setAccount(account);
        hold.setAmount(request.getAmount());
        hold.setCurrency(request.getCurrency());
        hold.setExpiresAt(request.getExpiresAt());
        return holdMapper.toDto(holdRepository.save(hold));
    }

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
