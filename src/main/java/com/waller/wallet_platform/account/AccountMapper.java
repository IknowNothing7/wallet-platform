package com.waller.wallet_platform.account;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(target = "userId", source = "user.id")
    AccountDto toAccountDto(Account account);

    @Mapping(target = "user", ignore = true)
    @Mapping(target = "version", ignore = true)
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
    Account createAnAccount(AccountRequest request);

}
