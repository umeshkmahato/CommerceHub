package com.commercehub.product.exception;

import java.time.Instant;

/**
 * Standard error envelope — every non-2xx response uses this shape so the
 * frontend and gateway can parse errors uniformly across services.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {}
