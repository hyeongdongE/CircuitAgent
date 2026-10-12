package com.circuitagent.design.api;

import com.circuitagent.design.model.ImportIssue;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.io.IOException;
import java.util.*;

@RestControllerAdvice(basePackageClasses = DesignController.class)
public class DesignApiExceptionHandler {
    public record ApiError(String code, String message, List<ImportIssue> issues) {}
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiError> notFound(NoSuchElementException e) { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage()); }
    @ExceptionHandler(ImportRejectedException.class)
    ResponseEntity<ApiError> parseFailure(ImportRejectedException e) {
        return ResponseEntity.badRequest().body(new ApiError("PARSE_FAILURE", e.getMessage(), e.issues()));
    }
    @ExceptionHandler(UnsupportedOperationException.class)
    ResponseEntity<ApiError> unsupported(UnsupportedOperationException e) { return error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_FORMAT", e.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException e) { return error(HttpStatus.CONFLICT, "IMPORT_CONFLICT", "Design name/version or imported record violates a uniqueness constraint"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) { return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Each file must be at most 10 MiB"); }
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class, MissingServletRequestPartException.class,
        MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class, IOException.class})
    ResponseEntity<ApiError> badRequest(Exception e) { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameters or input files"); }
    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, List.of()));
    }
}
