package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.entites.Transfer;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    @Mapping(target = "fromAccountId", source = "fromAccount.id")
    @Mapping(target = "toAccountId", source = "toAccount.id")
    TransferDto toDto(Transfer transfer);

}
