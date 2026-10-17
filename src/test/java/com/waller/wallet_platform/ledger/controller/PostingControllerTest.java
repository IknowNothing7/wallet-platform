package com.waller.wallet_platform.ledger.controller;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class PostingControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private Integer port;

    @Autowired
    private PostingRepository postingRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private PostingMapper postingMapper;
    @Autowired
    private AccountAccessChecker accountAccessChecker;
    @Autowired
    private OutboxEventService outboxEventService;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private AppUser alice;
    private AppUser bob;
    private Account aliceUsd;


    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        outboxRepository.deleteAll();
        accountRepository.deleteAll();
        appUserRepository.deleteAll();


        JournalEntry journalEntry = new JournalEntry();
        journalEntry.setId(9L);
        journalEntry.setEntryType(JournalEntryType.DEPOSIT);
        alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.ADMIN));
        aliceUsd = accountRepository.save(account(alice, 1000, "USD"));
        journalEntryRepository.save(journalEntry);

    }

    @Test
    void createPostingsAndBalanceChanged() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(
                        List.of(
                                new PostingRequest(aliceUsd.getId(), PostingDirection.DEBIT, 100L, "USD"),
                                new PostingRequest(aliceUsd.getId(), PostingDirection.CREDIT, 100L, "USD"))
                )
                .when()
                .post("/posting/journal-entry/{journalEntryId}" ,9L)

                .then()
                .statusCode(200);
    }

}
