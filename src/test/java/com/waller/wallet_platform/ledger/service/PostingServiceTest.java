package com.waller.wallet_platform.ledger.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

// Read side only; writes are LedgerServiceImplTest's
@ExtendWith(MockitoExtension.class)
public class PostingServiceTest {

        @Mock
        private PostingRepository postingRepository;

        @Mock
        private PostingMapper postingMapper;

        @Mock
        private AccountAccessChecker accountAccessChecker;

        @InjectMocks
        private PostingServiceImpl postingService;

    @Test
    void tryToGetPostingsFromDifferentAcc() {
            Posting posting = new Posting();
            posting.setAccountId(1L);
            when(postingRepository.findById(1L)).thenReturn(Optional.of(posting));
            when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> postingService.getPosting(1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Posting not found");
    }
}
