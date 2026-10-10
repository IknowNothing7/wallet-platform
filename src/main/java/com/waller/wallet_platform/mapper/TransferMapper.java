package com.waller.wallet_platform.mapper;

import com.waller.wallet_platform.model.request.TransferRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.entites.Transfer;

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
