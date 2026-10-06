package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.AppUserDto;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUserRequest;

@Mapper(componentModel = "spring")
public interface AppUserMapper {

    // passwordHash is set by the service after encoding the raw password
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "role", constant = "USER")
    @Mapping(target = "passwordHash", ignore = true)
    AppUser requestToEntity(AppUserRequest request);

    AppUserDto toDto(AppUser user);

}
