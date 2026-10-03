package com.seatbook.error;

import com.seatbook.dto.responses.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(exception = DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException domainException) {
        ErrorResponse errorResponse = new ErrorResponse(domainException.getErrorCode().getValue(), domainException.getMessage());
        return new ResponseEntity<>(errorResponse, domainException.getErrorCode().getHttpStatus());
    }

    @Override
    public ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException methodArgumentNotValidException, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Invalid request";
        }
        ErrorResponse errorResponse = new ErrorResponse("validation_error", message);
        return new ResponseEntity<>(errorResponse, status);
    }

    @Override
    public ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code;
        String message;
        switch (status.value()) {
            case 400 -> { code = ErrorCode.VALIDATION_ERROR;        message = "Invalid request"; }
            case 404 -> { code = ErrorCode.NOT_FOUND;               message = "Resource not found"; }
            case 405 -> { code = ErrorCode.METHOD_NOT_ALLOWED;      message = "Method not allowed"; }
            case 415 -> { code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;  message = "Unsupported media type"; }
            default -> {
                code = ErrorCode.INTERNAL_ERROR;
                message = "Unexpected error";
                if (status.is5xxServerError()) {
                    logger.error("Unhandled framework exception", exception);
                }
            }
        }
        return ResponseEntity.status(status).headers(headers)
                .body(new ErrorResponse(code.getValue(), message));
    }

    @ExceptionHandler(exception = Exception.class)
    public ResponseEntity<ErrorResponse> handleInternalException(Exception e) {
        logger.error("Unhandled", e);
        ErrorResponse errorResponse = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "internal server error occurr");
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
