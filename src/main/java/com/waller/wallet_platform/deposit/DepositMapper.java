package com.waller.wallet_platform.deposit;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DepositMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "account", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "journalEntry", ignore = true)
    @Mapping(target = "failureReason", ignore = true)
    @Mapping(target = "expiresAt", ignore = true)
    Deposit requestToEntity(DepositRequest request);

    @Mapping(target = "accountId", source = "account.id")
    DepositDto toDto(Deposit deposit);

}
