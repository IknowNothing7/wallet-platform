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
import com.waller.wallet_platform.common.idempotency.IdempotencyService;
import com.waller.wallet_platform.common.web.PageResponse;
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
    private TransferProcessingService transferProcessingService;

    @Mock
    private IdempotencyService idempotencyService;

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

    // Checking the accounts and booking are TransferProcessingService's (TransferProcessingServiceTest)
    @Test
    void createTransfer_booksItAtOnce() {
        TransferDto dto = TransferDto.builder().id(10L).status(TransferStatus.COMPLETED).build();
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(transferProcessingService.book(request)).thenReturn(dto);

        assertThat(transferService.createTransfer(request, null)).isSameAs(dto);
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void createTransfer_repeatedIdempotencyKey_returnsStoredTransferAndBooksNothing() {
        TransferDto previous = TransferDto.builder().id(10L).status(TransferStatus.COMPLETED).build();
        when(idempotencyService.findResponse("key-1", "POST /api/transfers", request, TransferDto.class))
                .thenReturn(Optional.of(previous));

        TransferDto result = transferService.createTransfer(request, "key-1");

        assertThat(result).isSameAs(previous);
        verifyNoInteractions(transferProcessingService);
        verify(idempotencyService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createTransfer_newIdempotencyKey_storesTheResponse() {
        TransferDto dto = TransferDto.builder().id(10L).status(TransferStatus.FAILED).build();
        when(idempotencyService.findResponse("key-1", "POST /api/transfers", request, TransferDto.class))
                .thenReturn(Optional.empty());
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(transferProcessingService.book(request)).thenReturn(dto);

        TransferDto result = transferService.createTransfer(request, "key-1");

        assertThat(result).isEqualTo(dto);
        verify(idempotencyService).save("key-1", "POST /api/transfers", request, HttpStatus.CREATED, dto);
    }

    @Test
    void createTransfer_sourceAccountOfAnotherUser_answersLikeMissingAccount() {
        when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> transferService.createTransfer(request, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Account 1 not found");

        verifyNoInteractions(transferProcessingService);
    }

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
