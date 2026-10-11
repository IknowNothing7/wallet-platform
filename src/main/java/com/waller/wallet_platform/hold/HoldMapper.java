package com.waller.wallet_platform.hold;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HoldMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "capturedEntryId", source = "capturedEntry.id")
    HoldDto toDto(Hold hold);

}
