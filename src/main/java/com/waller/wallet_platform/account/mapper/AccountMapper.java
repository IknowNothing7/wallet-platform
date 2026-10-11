package com.waller.wallet_platform.account.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.dto.AccountRequest;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(target = "userId", source = "user.id")
    AccountDto toAccountDto(Account account);

    @Mapping(target = "user", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "systemKind", ignore = true)
    Account toAccountEntity(AccountDto dto);

    // balance, status and type keep the entity defaults; user is resolved by the service
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "balance", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "systemKind", ignore = true)
    Account createAnAccount(AccountRequest request);

}
