package com.waller.wallet_platform.ledger;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.service.TransferProcessingService;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

// The ledger's invariants hold in the schema itself (V11), not only in LedgerServiceImpl: whatever writes to the
// database, raw SQL included, can't edit history, book an object twice or drive a user negative.
class LedgerIntegrityTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SystemAccountService systemAccountService;

    @Autowired
    private TransferProcessingService transferProcessingService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private Account aliceUsd;
    private TransferDto booked;

    @BeforeEach
    void setUp() {
        aliceUsd = accountRepository.save(account(
                appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER)), 1_000, "USD"));
        Account bobUsd = accountRepository.save(account(
                appUserRepository.save(user("Bob", "bob@email.com", UserRole.USER)), 0, "USD"));
        booked = transferProcessingService.book(new TransferRequest(aliceUsd.getId(), bobUsd.getId(), 100L, "USD"));
    }

    @Test
    void noHttpEndpointWritesToTheLedger() {
        Set<RequestMethod> ledgerMethods = new java.util.HashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getPackageName().startsWith("com.waller.wallet_platform.ledger")) {
                ledgerMethods.addAll(info.getMethodsCondition().getMethods());
            }
        });
        assertThat(ledgerMethods).containsOnly(RequestMethod.GET);
    }

    @Test
    void postingsCanNotBeUpdatedOrDeleted() {
        assertAppendOnly("UPDATE posting SET amount = amount + 1");
        assertAppendOnly("DELETE FROM posting");
    }

    @Test
    void journalEntriesCanNotBeUpdatedOrDeleted() {
        assertAppendOnly("UPDATE journal_entry SET description = 'edited'");
        assertAppendOnly("DELETE FROM journal_entry");
    }

    @Test
    void anObjectIsBookedAtMostOncePerEntryType() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO journal_entry (entry_type, reference_type, reference_id)
                VALUES ('TRANSFER', 'transfer', ?)""", booked.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_journal_entry_reference");
    }

    @Test
    void aUserBalanceCanNotGoNegative() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE account SET balance = -1 WHERE id = ?", aliceUsd.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_account_balance_non_negative");
    }

    // BANK_CLEARING is the platform's position against the bank, negative by design
    @Test
    void aSystemBalanceMayGoNegative() {
        Account clearing = systemAccountService.get(SystemAccountKind.BANK_CLEARING, "USD");

        assertThat(jdbcTemplate.update("UPDATE account SET balance = -500 WHERE id = ?", clearing.getId())).isOne();
    }

    private void assertAppendOnly(String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }
}
