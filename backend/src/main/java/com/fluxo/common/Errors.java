package com.fluxo.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Shorthands for RFC 9457 errors; `code` lets the UI react without parsing messages. */
public final class Errors {

    private Errors() {
    }

    public static ErrorResponseException of(HttpStatus status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("code", code);
        return new ErrorResponseException(status, pd, null);
    }

    public static ErrorResponseException badRequest(String code, String message) {
        return of(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ErrorResponseException notFound(String message) {
        return of(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ErrorResponseException forbidden(String message) {
        return of(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ErrorResponseException conflict(String code, String message) {
        return of(HttpStatus.CONFLICT, code, message);
    }
}
