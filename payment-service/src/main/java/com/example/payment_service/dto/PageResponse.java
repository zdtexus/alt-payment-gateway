package com.example.payment_service.dto;

import java.util.List;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> content, @Schema(description = "Zero-based page number", example = "0") int page,
		@Schema(example = "20") int size, @Schema(example = "1") long totalElements,
		@Schema(example = "1") int totalPages) {

	public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}

}
