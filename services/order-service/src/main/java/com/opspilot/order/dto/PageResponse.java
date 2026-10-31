package com.opspilot.order.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// Our own stable page format for the API. Spring's Page object is internal and not meant to be sent as JSON.
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
