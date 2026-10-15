package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferRepository;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferMapper transferMapper;

    @Mock
    private AccountAccessChecker accountAccessChecker;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private TransferServiceImpl transferService;

    private Account from;
    private Account to;
    private TransferRequest request;

    @BeforeEach
    void setUp() {
        from = account(1L, "USD");
        to = account(2L, "USD");
        request = new TransferRequest(from.getId(), to.getId(), 100L, "USD");
    }

    @Test
    void createTransfer_savesTransferBetweenLoadedAccountsAndRecordsOutboxEvent() {
        Transfer mapped = new Transfer();
        mapped.setAmount(100L);
        mapped.setCurrency("USD");
        Transfer saved = new Transfer();
        saved.setId(10L);
        saved.setAmount(100L);
        saved.setCurrency("USD");
        TransferDto dto = TransferDto.builder()
                .id(10L).fromAccountId(1L).toAccountId(2L).amount(100L).currency("USD")
                .status(TransferStatus.PENDING).build();

        when(accountRepository.findUserAccountById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.of(to));
        when(transferMapper.requestToEntity(request)).thenReturn(mapped);
        when(transferRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(transferMapper.toDto(saved)).thenReturn(dto);

        TransferDto result = transferService.createTransfer(request);

        assertThat(result).isEqualTo(dto);
        // The mapper only knows the ids; the service must swap in the loaded accounts
        assertThat(saved.getFromAccount()).isSameAs(from);
        assertThat(saved.getToAccount()).isSameAs(to);
        verify(outboxEventService).record("transfer", 10L, "TransferCreated", dto);
    }

    @Test
    void createTransfer_unknownSourceAccount_throwsNotFoundAndSavesNothing() {
        when(accountRepository.findUserAccountById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.createTransfer(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Account 1 not found");

        verify(transferRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void createTransfer_currencyMismatch_throwsBadRequestAndSavesNothing() {
        Account eurAccount = account(2L, "EUR");
        when(accountRepository.findUserAccountById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.of(eurAccount));

        assertThatThrownBy(() -> transferService.createTransfer(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("EUR");

        verify(transferRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    // TODO: Missing: an unknown destination account. Its message currently names the source id
    //  (TransferServiceImpl.java:292); fix that first, then assert "Account 2 not found".
    @Test
    void getTransfer_visibleToOwnerOfReceivingAccount() {
        Transfer transfer = new Transfer();
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        TransferDto dto = TransferDto.builder().id(10L).build();
        when(transferRepository.findWithAccountsById(10L)).thenReturn(Optional.of(transfer));
        when(accountAccessChecker.canAccess(from)).thenReturn(false);
        when(accountAccessChecker.canAccess(to)).thenReturn(true);
        when(transferMapper.toDto(transfer)).thenReturn(dto);

        assertThat(transferService.getTransfer(10L)).isEqualTo(dto);
    }

    @Test
    void getTransfer_noAccessToEitherSide_answersLikeMissingTransfer() {
        Transfer transfer = new Transfer();
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        when(transferRepository.findWithAccountsById(10L)).thenReturn(Optional.of(transfer));
        when(accountAccessChecker.canAccess(any(Account.class))).thenReturn(false);

        assertThatThrownBy(() -> transferService.getTransfer(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Transfer not found");

        verifyNoInteractions(transferMapper);
    }

    @Test
    void getAccountTransfers_returnsMappedPage() {
        Transfer transfer = new Transfer();
        TransferDto dto = TransferDto.builder().id(10L).build();
        PageRequest pageable = PageRequest.of(1, 10);
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(transferRepository.findByAccountId(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(transfer), pageable, 11));
        when(transferMapper.toDto(transfer)).thenReturn(dto);

        PageResponse<TransferDto> result = transferService.getAccountTransfers(1L, 1, 10);

        assertThat(result.content()).containsExactly(dto);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void getAccountTransfers_noAccess_throwsNotFound() {
        when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> transferService.getAccountTransfers(1L, 0, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verify(transferRepository, never()).findByAccountId(anyLong(), any());
    }

}
