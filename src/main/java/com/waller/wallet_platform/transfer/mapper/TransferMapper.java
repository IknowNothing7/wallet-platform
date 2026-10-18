package com.waller.wallet_platform.transfer.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.Transfer;

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
    @Mapping(target = "attempts", ignore = true)
    @Mapping(target = "nextAttemptAt", ignore = true)
    Transfer requestToEntity(TransferRequest request);

}
