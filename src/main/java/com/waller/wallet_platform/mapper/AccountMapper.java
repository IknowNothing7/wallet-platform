package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.AccountDto;
import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.request.AccountRequest;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    AccountDto toAccountDto(Account account);

    Account toAccountEntity(AccountDto dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "deleted", constant = "false")
    Account createAnAccount(AccountRequest request);

}
