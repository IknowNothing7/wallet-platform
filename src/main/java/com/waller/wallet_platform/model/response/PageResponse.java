package com.waller.wallet_platform.model.response;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

// Stable JSON shape for paged results; Spring Data's Page serializes inconsistently between versions
public record PageResponse<T extends Serializable>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages

) implements Serializable {

    public static <E, T extends Serializable> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

}
