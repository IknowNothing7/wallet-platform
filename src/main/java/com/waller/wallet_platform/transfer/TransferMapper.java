package com.waller.wallet_platform.transfer;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    @Mapping(target = "fromAccountId", source = "fromAccount.id")
    @Mapping(target = "toAccountId", source = "toAccount.id")
    TransferDto toDto(Transfer transfer);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "fromAccount.id", source = "idFromAccount")
    @Mapping(target = "toAccount.id", source = "idToAccount")
    @Mapping(target = "journalEntry", ignore = true)
    @Mapping(target = "failureReason", ignore = true)
    Transfer requestToEntity(TransferRequest request);

}
