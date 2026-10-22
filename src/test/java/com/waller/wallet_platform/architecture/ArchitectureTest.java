package com.waller.wallet_platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;

import jakarta.persistence.Entity;

// Architecture rules, checked on the compiled main classes. The code is packaged by feature (account, deposit,
// ledger, ...), each feature layered as controller -> service -> repository, with entity/dto/mapper alongside.
// Rules that hold today are on; the ones the code does not meet yet are @Disabled with what blocks them.
// Plain ArchUnit (no archunit-junit5 engine): its engine pins a JUnit 5 launcher, Spring Boot 4 runs JUnit 6.
class ArchitectureTest {

    private static final String ROOT = "com.waller.wallet_platform";

    // Feature modules. common, config and security are shared infrastructure, not features.
    private static final String[] FEATURES =
            {"account", "auth", "bank", "deposit", "hold", "ledger", "outbox", "transfer", "user"};

    // Small single-purpose packages that are not split into controller/entity/repository sub-packages.
    private static final String BANK_WEBHOOK = ROOT + ".bank.webhook";
    private static final String SECURITY = ROOT + ".security";
    private static final String IDEMPOTENCY = ROOT + ".common.idempotency";

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

    @Test
    void entitiesDoNotDependOnDtos() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..dto..", "..mapper..")
                .because("the mapper depends on the entity and the dto, not the other way round")
                .check(classes);
    }

    // --- Placement ---

    @Test
    void restControllersLiveInControllerPackages() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAnyPackage("..controller..", BANK_WEBHOOK, SECURITY)
                .check(classes);
    }

    @Test
    void entitiesLiveInEntityPackages() {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAnyPackage("..entity..", BANK_WEBHOOK, IDEMPOTENCY)
                .check(classes);
    }

    @Test
    void repositoriesLiveInRepositoryPackages() {
        classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
                .or().areAnnotatedWith(Repository.class)
                .should().resideInAnyPackage("..repository..", BANK_WEBHOOK, IDEMPOTENCY)
                .check(classes);
    }

    // Native queries (outbox claiming, ...) live on the Spring Data interface too. A JdbcTemplate-based repository
    // would have to be listed here.
    @Test
    void repositoriesAreSpringDataInterfaces() {
        classes().that().haveSimpleNameEndingWith("Repository")
                .should().beInterfaces()
                .andShould().beAssignableTo(org.springframework.data.repository.Repository.class)
                .check(classes);
    }

    // --- Naming ---

    @Test
    void controllersAreNamedController() {
        classes().that().resideInAPackage("..controller..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Controller")
                .andShould().beAnnotatedWith(RestController.class)
                .check(classes);
        classes().that().areAnnotatedWith(RestController.class)
                .should().haveSimpleNameEndingWith("Controller")
                .check(classes);
    }

    @Test
    void servicesAreNamedService() {
        classes().that().areAnnotatedWith(Service.class)
                .should().haveSimpleNameEndingWith("Service").orShould().haveSimpleNameEndingWith("ServiceImpl")
                .check(classes);
        classes().that().resideInAPackage("..service..").and().areInterfaces()
                .should().haveSimpleNameEndingWith("Service")
                .check(classes);
        classes().that().haveSimpleNameEndingWith("ServiceImpl")
                .should(implementTheInterfaceTheyAreNamedAfter())
                .check(classes);
    }

    // AppUserSpecifications (Specification factories for AppUserRepository) is the one class in a repository package.
    @Test
    void repositoryPackagesHoldRepositories() {
        classes().that().resideInAPackage("..repository..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Repository").orShould().haveSimpleNameEndingWith("Specifications")
                .check(classes);
    }

    // *MapperImpl are MapStruct's generated implementations.
    @Test
    void mappersAreNamedMapper() {
        classes().that().resideInAPackage("..mapper..").and().areInterfaces()
                .should().haveSimpleNameEndingWith("Mapper")
                .check(classes);
    }

    @Test
    void jobsAreNamedJob() {
        classes().that().resideInAPackage("..job..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Job")
                .check(classes);
    }

    // TODO: decide the dto naming. Today: bank.dto.BankPayment and BankPaymentResult (named after the bank's API),
    //  user.dto.UserEvent (an outbox payload). Rename them, or allow those suffixes here, then enable.
    @Disabled("TODO: BankPayment, BankPaymentResult, UserEvent")
    @Test
    void dtosAreNamedDtoRequestOrResponse() {
        classes().that().resideInAPackage("..dto..").and().areTopLevelClasses().and().areNotEnums()
                .should().haveSimpleNameEndingWith("Dto")
                .orShould().haveSimpleNameEndingWith("Request")
                .orShould().haveSimpleNameEndingWith("Response")
                .check(classes);
    }

    // --- Transactions ---

    // Besides the feature services: AppUserDetailsService and AccountAccessChecker (security) read users and
    // accounts in a read-only transaction, and IdempotencyService lives in the flat common.idempotency package.
    @Test
    void transactionsStartInServices() {
        classes().that().areAnnotatedWith(Transactional.class)
                .or().containAnyMethodsThat(CanBeAnnotated.Predicates.annotatedWith(Transactional.class))
                .should().resideInAnyPackage("..service..", SECURITY, IDEMPOTENCY)
                .check(classes);
    }

    @Test
    void controllersAreNotTransactional() {
        String because = "a transaction around the whole request holds locks and connections while the response is"
                + " written";
        noClasses().that().resideInAPackage("..controller..").or().areAnnotatedWith(RestController.class)
                .should().beAnnotatedWith(Transactional.class)
                .because(because)
                .check(classes);
        noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should().beAnnotatedWith(Transactional.class)
                .because(because)
                .check(classes);
    }

    // --- Ledger: the only way to move money ---

    // A dependency rule, not onlyBeAccessed(): the callers use getReferenceById, which ArchUnit attributes to
    // JpaRepository, so an access rule passes while the dependency is there.
    // TODO: deposit (DepositProcessingServiceImpl), hold (HoldServiceImpl) and transfer
    //  (TransferProcessingServiceImpl) inject JournalEntryRepository only to call getReferenceById(entry.id()).
    //  Have LedgerService return that reference (or store the journal entry id), then enable.
    @Disabled("TODO: deposit, hold and transfer use ledger.repository directly")
    @Test
    void onlyTheLedgerUsesLedgerRepositories() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".ledger..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".ledger.repository..")
                .check(classes);
    }

    // TODO: JournalEntry and Posting carry a class-level @Setter, and LedgerServiceImpl.post builds them with
    //  setters. Replace that with a builder or constructor, drop @Setter, then enable.
    @Disabled("TODO: @Setter on JournalEntry and Posting")
    @Test
    void ledgerEntitiesAreImmutable() {
        methods().that().areDeclaredInClassesThat().resideInAPackage(ROOT + ".ledger.entity")
                .and().haveNameStartingWith("set")
                .should().notBePublic()
                .allowEmptyShould(true)
                .because("a posted journal entry is never changed, only reversed by a new one")
                .check(classes);
    }

    // --- Module boundaries ---

    // A module's repository and entity packages are its own; other modules go through its service (and dto).
    // TODO: today account.repository is used by deposit, hold, ledger, security (AccountAccessChecker) and
    //  transfer; user.repository by account, auth, common (IdempotencyService), hold and security; hold.repository
    //  by transfer; ledger.repository by deposit, hold and transfer. Decide per case: add a service method, or
    //  accept the dependency and exempt it here.
    @Disabled("TODO: modules use each other's repositories")
    @Test
    void repositoriesAreModulePrivate() {
        classes().that().resideInAnyPackage(featurePackages())
                .or().resideInAnyPackage(ROOT + ".common..", ROOT + ".config..", SECURITY + "..")
                .should(onlyDependOnTheirOwnModules("repository"))
                .check(classes);
    }

    // TODO: entities reference each other across modules through JPA associations: Deposit, Hold and Transfer map
    //  Account and JournalEntry; Account, Hold, RefreshToken and IdempotencyKey map AppUser. Services also use the
    //  enums AccountStatus, SystemAccountKind, JournalEntryType, PostingDirection and HoldStatus. Decide whether
    //  cross-module associations stay (then delete this rule) or become plain ids, with the shared enums moved to
    //  dto.
    @Disabled("TODO: cross-module JPA associations and enums")
    @Test
    void entitiesAreModulePrivate() {
        classes().that().resideInAnyPackage(featurePackages())
                .or().resideInAnyPackage(ROOT + ".common..", ROOT + ".config..", SECURITY + "..")
                .should(onlyDependOnTheirOwnModules("entity"))
                .check(classes);
    }

    // TODO: current cycles:
    //  - bank <-> deposit: BankWebhookController calls deposit.service, deposit calls bank.client.
    //  - auth <-> user: AppUserMapper maps auth.dto.RegistrationRequest.
    //  - account <-> security: account services use AccountAccessChecker, which reads account.repository.
    //  - common -> security / user (see commonIsALeaf), which closes a loop with every module that uses common.
    //  Break them (move the shared DTO, have the webhook publish an event, move AccountAccessChecker into
    //  account, ...) and enable.
    @Disabled("TODO: bank <-> deposit, auth <-> user, account <-> security, common -> security/user")
    @Test
    void modulesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    // TODO: common.util.ApiUtils uses security.JwtTokenProvider; common.idempotency.IdempotencyKey and
    //  IdempotencyService use user.entity.AppUser and user.repository.AppUserRepository. Pass the user id in
    //  (or move idempotency into its own module), then enable.
    @Disabled("TODO: ApiUtils -> security, idempotency -> user")
    @Test
    void commonIsALeaf() {
        noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(featurePackages())
                .orShould().dependOnClassesThat().resideInAnyPackage(ROOT + ".config..", SECURITY + "..")
                .check(classes);
    }

    // config/ and security/ may depend on features (they wire them); the reverse is not allowed for config/.
    @Test
    void featuresDoNotDependOnConfig() {
        noClasses().that().resideInAnyPackage(featurePackages())
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".config..")
                .check(classes);
    }

    // --- Outbox ---

    @Test
    void onlyTheOutboxPublishesToKafka() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".outbox..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .because("events leave through the outbox, in the same transaction as the change")
                .check(classes);
    }

    // config.ObservabilityConfig is allowed to name OutboxPublisher (it keeps the poll loop out of tracing).
    @Test
    void otherModulesUseOnlyTheOutboxService() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".outbox..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".outbox.entity..", ROOT + ".outbox.repository..", ROOT + ".outbox.messaging..",
                        ROOT + ".outbox.mapper..", ROOT + ".outbox.controller..")
                .because("other modules record events through OutboxEventService, inside their own transaction")
                .check(classes);
    }

    // --- General hygiene ---

    @Test
    void noAutowiredFields() {
        noFields()
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould().beAnnotatedWith("jakarta.inject.Inject")
                .orShould().beAnnotatedWith("jakarta.annotation.Resource")
                .because("dependencies come in through the constructor (@RequiredArgsConstructor)")
                .check(classes);
    }

    // The full ArchUnit rule also counts @Value fields.
    // TODO: @Value fields: AuthServiceImpl (accessTokenLifetimeMillis, failedLoginWindow, maxFailedLogins),
    //  RefreshTokenServiceImpl.refreshLifetime, SecurityConfig.allowedOrigins, DepositServiceImpl.pendingTtl,
    //  HoldServiceImpl.maxTtl, JwtTokenProvider.lifetime. Move them to constructor parameters (or a
    //  @ConfigurationProperties record), so unit tests don't need ReflectionTestUtils, then enable.
    @Disabled("TODO: @Value fields in 7 classes")
    @Test
    void noFieldInjection() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(classes);
    }

    @Test
    void noJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    }

    @Test
    void noStandardStreams() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    }

    @Test
    void noBigDecimalFromDouble() {
        noClasses().should().callConstructor(BigDecimal.class, double.class)
                .because("new BigDecimal(0.1) is 0.1000000000000000055...; use BigDecimal.valueOf or a String")
                .check(classes);
    }

    // TODO: once the disabled rules are close, consider FreezingArchRule instead of @Disabled, so new violations
    //  fail while the existing ones are paid down (needs a committed violation store).

    // --- Helpers ---

    private static String[] featurePackages() {
        return Arrays.stream(FEATURES).map(feature -> ROOT + "." + feature + "..").toArray(String[]::new);
    }

    // Top-level package under ROOT (account, common, security, ...), or null for ROOT itself.
    private static String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(ROOT + ".")) {
            return null;
        }
        return packageName.substring(ROOT.length() + 1).split("\\.")[0];
    }

    private static ArchCondition<JavaClass> onlyDependOnTheirOwnModules(String layer) {
        return new ArchCondition<>("only use the " + layer + " packages of their own module") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String module = moduleOf(javaClass);
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    String targetModule = moduleOf(dependency.getTargetClass());
                    if (targetModule != null && !targetModule.equals(module) && dependency.getTargetClass()
                            .getPackageName().startsWith(ROOT + "." + targetModule + "." + layer)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> implementTheInterfaceTheyAreNamedAfter() {
        return new ArchCondition<>("implement the interface they are named after") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String expected = javaClass.getSimpleName().replaceFirst("Impl$", "");
                boolean implementsIt = javaClass.getRawInterfaces().stream()
                        .anyMatch(anInterface -> anInterface.getSimpleName().equals(expected));
                events.add(new SimpleConditionEvent(javaClass, implementsIt,
                        javaClass.getName() + " does not implement " + expected));
            }
        };
    }
}
