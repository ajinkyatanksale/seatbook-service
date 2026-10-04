package com.seatbook.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    SEAT_TAKEN(HttpStatus.CONFLICT, "seat_taken"),
    PER_USER_LIMIT(HttpStatus.CONFLICT, "per_user_limit"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "idempotency_conflict"),
    UNKNOWN_SEAT(HttpStatus.NOT_FOUND, "unknown_seat"),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "show_not_found"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "reservation_not_found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST,"validation_error"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error")
    ;

    private final String value;
    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus, String value) {
        this.httpStatus = httpStatus;
        this.value = value;
    }

    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    public String getValue() {
        return this.value;
    }
}
