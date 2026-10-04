package io.github.xssirr17.reserve.slot.service;

import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public record SlotPageCacheDto(
    List<SlotResponse> content,
    int pageNumber,
    int pageSize,
    long totalElements
) {
    public static SlotPageCacheDto from(Page<SlotResponse> page) {
        return new SlotPageCacheDto(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements()
        );
    }

    public Page<SlotResponse> toPage() {
        return new PageImpl<>(
            content,
            PageRequest.of(pageNumber, Math.max(pageSize, 1)),
            totalElements
        );
    }
}
