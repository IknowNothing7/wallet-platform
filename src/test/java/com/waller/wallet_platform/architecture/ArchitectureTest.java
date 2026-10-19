package com.waller.wallet_platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import jakarta.persistence.Entity;

// Architecture rules, checked on the compiled main classes. The code is packaged by feature (account, deposit,
// ledger, ...), each feature layered as controller -> service -> repository, with entity/dto/mapper alongside.
// Rules that hold today are on; the ones the code does not meet yet are @Disabled with what blocks them.
// Plain ArchUnit (no archunit-junit5 engine): its engine pins a JUnit 5 launcher, Spring Boot 4 runs JUnit 6.
class ArchitectureTest {

    private static final String ROOT = "com.waller.wallet_platform";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    // --- Layers within a feature ---

    @Test
    void controllersDoNotUseRepositories() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("controllers go through a service, which owns the transaction")
                .check(classes);
    }

    @Test
    void repositoriesDoNotUseServicesOrControllers() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
                .check(classes);
    }

    @Test
    void servicesDoNotUseControllers() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .check(classes);
    }

    // TODO: entities should not know DTOs or mappers (the mapper depends on both, not the other way round).
    //  Check whether any entity has a from(dto)/toDto() helper before enabling.
    @Disabled("TODO: verify against the current entities")
    @Test
    void entitiesDoNotDependOnDtos() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..dto..", "..mapper..")
                .check(classes);
    }

    // --- Placement and naming ---

    // TODO: flat packages break this: bank.webhook.BankWebhookController, security.JwksController.
    //  Move them, or exempt bank.webhook / security with resideInAnyPackage(...).
    @Disabled("TODO: BankWebhookController, JwksController")
    @Test
    void restControllersLiveInControllerPackages() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..controller..")
                .check(classes);
    }

    // TODO: flat packages break this: bank.webhook.BankWebhookDeadLetter, common.idempotency.IdempotencyKey.
    @Disabled("TODO: BankWebhookDeadLetter, IdempotencyKey")
    @Test
    void entitiesLiveInEntityPackages() {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAPackage("..entity..")
                .check(classes);
    }

    // TODO: decide whether repositories are always Spring Data interfaces (then: "should be interfaces"),
    //  or JdbcTemplate classes are allowed too (ShedLock, outbox claiming).
    // TODO: common.idempotency.IdempotenceRepository breaks this (flat package).
    @Disabled("TODO: IdempotenceRepository")
    @Test
    void repositoriesLiveInRepositoryPackages() {
        classes().that().areAnnotatedWith(Repository.class)
                .should().resideInAPackage("..repository..")
                .allowEmptyShould(true)
                .check(classes);
    }

    // TODO: naming conventions, e.g. *Controller, *Service / *ServiceImpl, *Repository, *Mapper, *Request / *Response.
    //  Write them once the exceptions are known (listeners, jobs, AccountAccessChecker, ...).

    // --- Transactions ---

    // TODO: @Transactional only on services. Today it is also in security/ and common/idempotency/; either allow
    //  those packages explicitly or move the transaction into a service.
    @Disabled("TODO: list the allowed exceptions")
    @Test
    void transactionsStartInServices() {
        classes().that().areAnnotatedWith(Transactional.class)
                .or().containAnyMethodsThat(CanBeAnnotated.Predicates.annotatedWith(Transactional.class))
                .should().resideInAPackage("..service..")
                .check(classes);
    }

    // TODO: controllers must not be @Transactional (a transaction around the whole request).

    // --- Ledger: the only way to move money ---

    // TODO: only the ledger writes journal entries / postings. Today deposit, hold and transfer import
    //  ledger.repository directly; route them through ledger.service, then enable.
    @Disabled("TODO: deposit, hold and transfer use ledger.repository directly")
    @Test
    void onlyTheLedgerUsesLedgerRepositories() {
        classes().that().resideInAPackage(ROOT + ".ledger.repository..")
                .should().onlyBeAccessed().byAnyPackage(ROOT + ".ledger..")
                .check(classes);
    }

    // TODO: ledger entities are immutable once posted: no public setters on JournalEntry / Posting.

    // --- Module boundaries ---

    // TODO: a feature exposes its service (and dto) packages; its repository and entity packages are private.
    //  Today account.repository is used by deposit, hold, transfer and ledger; user.repository by account, auth
    //  and hold. Decide per case: add a service method, or accept the dependency and list it here.
    // TODO: generalise to every module (one test per module, or a custom ArchCondition comparing the
    //  accessor's module with the target's).
    @Disabled("TODO: modules use each other's repositories")
    @Test
    void accountRepositoriesArePrivate() {
        classes().that().resideInAPackage(ROOT + ".account.repository..")
                .should().onlyBeAccessed().byAnyPackage(ROOT + ".account..")
                .check(classes);
    }

    // TODO: no cycles between modules. Current cycles: bank <-> deposit (the webhook calls deposit.service,
    //  deposit calls bank.client) and auth <-> user (user imports auth.dto). Break them (move the shared DTO,
    //  have the webhook publish an event, ...) and enable.
    @Disabled("TODO: bank <-> deposit and auth <-> user cycles")
    @Test
    void modulesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    // TODO: common/ is a leaf: it may not depend on any feature module.
    // TODO: config/ and security/ may depend on features; features do not depend on config/.

    // --- Outbox ---

    @Test
    void onlyTheOutboxPublishesToKafka() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".outbox..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .because("events leave through the outbox, in the same transaction as the change")
                .check(classes);
    }

    // TODO: other modules depend only on outbox.service, not on outbox.entity / outbox.repository / messaging.

    // --- General hygiene ---

    // TODO: no field injection (GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION).
    // TODO: no java.util.logging / System.out (GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING,
    //  NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS).
    // TODO: no new BigDecimal(double) anywhere money is handled.
    // TODO: once the disabled rules are close, consider FreezingArchRule instead of @Disabled, so new violations
    //  fail while the existing ones are paid down.
}
