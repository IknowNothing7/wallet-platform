package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.HoldDto;
import com.waller.wallet_platform.model.entites.Hold;

@Mapper(componentModel = "spring")
public interface HoldMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "capturedEntryId", source = "capturedEntry.id")
    HoldDto toDto(Hold hold);

}
