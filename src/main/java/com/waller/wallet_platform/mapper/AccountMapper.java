package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.AccountDto;
import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.request.AccountRequest;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "userId", ignore = true)
    AccountDto toAccountDto(Account account);

    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "version", ignore = true)
    Account toAccountEntity(AccountDto dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "user", ignore = true)
    Account createAnAccount(AccountRequest request);

}
