package com.waller.wallet_platform.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.entites.Posting;

@Mapper(componentModel = "spring")
public interface PostingMapper {

    PostingDto toDto(Posting posting);

    List<PostingDto> toDtos(List<Posting> postings);

}
