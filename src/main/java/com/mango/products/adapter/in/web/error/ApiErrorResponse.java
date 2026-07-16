package com.mango.products.adapter.in.web.error;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<ApiViolation> violations
) {
    public ApiErrorResponse {
        violations = List.copyOf(violations);
    }
}
