package com.waller.wallet_platform.ledger.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.entity.Posting;

@Mapper(componentModel = "spring")
public interface PostingMapper {

    PostingDto toDto(Posting posting);

    List<PostingDto> toDtos(List<Posting> postings);

}
