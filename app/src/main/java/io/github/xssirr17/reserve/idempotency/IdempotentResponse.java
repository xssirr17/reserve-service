package io.github.xssirr17.reserve.idempotency;

import java.util.Collections;
import java.util.Map;

public record IdempotentResponse<T>(int statusCode, Map<String, String> headers, T body) {

    public IdempotentResponse {
        if (headers == null) {
            headers = Collections.emptyMap();
        }
    }

    public static <T> IdempotentResponse<T> of(int statusCode, Map<String, String> headers, T body) {
        return new IdempotentResponse<>(statusCode, headers, body);
    }

    public static <T> IdempotentResponse<T> ok(T body) {
        return new IdempotentResponse<>(200, Collections.emptyMap(), body);
    }
}
