package com.waller.wallet_platform.support;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;

// TODO: Deposit and outbox tests build entities by hand in setUp(); add factories here (hold, outboxEvent) as they are needed.
// Shared factories for test entities. Each one fills in only the required fields;
// tests set whatever else they care about so the values they assert on stay visible.
public final class TestData {

    private TestData() {
    }

    public static AppUser user(String name, String email, UserRole role) {
        return AppUser.builder().name(name).email(email).passwordHash("unused").role(role).build();
    }

    // Unsaved account, ready to persist in integration tests
    public static Account account(AppUser user, long balance, String currency) {
        Account account = new Account();
        account.setUser(user);
        account.setBalance(balance);
        account.setCurrency(currency);
        return account;
    }

    // Account with an id, for unit tests where nothing is persisted
    public static Account account(Long id, String currency) {
        Account account = new Account();
        account.setId(id);
        account.setCurrency(currency);
        return account;
    }

    public static Deposit testDeposit( Account account, int amount, String currency, DepositStatus status,String gateway, String gatewayReference) {
        Deposit deposit = new Deposit();
        deposit.setAccount(account);
        deposit.setAmount(amount);
        deposit.setCurrency(currency);
        deposit.setStatus(status);
        deposit.setGateway(gateway);
        deposit.setGatewayReference(gatewayReference);
        return deposit;
    }

    public static Transfer transfer(Account from, Account to, long amount, String currency) {
        Transfer transfer = new Transfer();
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        transfer.setAmount(amount);
        transfer.setCurrency(currency);
        return transfer;
    }
}
