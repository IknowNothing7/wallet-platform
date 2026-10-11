package com.waller.wallet_platform.ledger;

import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PostingMapper {

    PostingDto toDto(Posting posting);

    List<PostingDto> toDtos(List<Posting> postings);

}
