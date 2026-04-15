package com.hls.controller;

import com.hls.controller.dto.ErrorResponse;
import com.hls.controller.dto.ReloadResponse;
import com.hls.loader.LoaderValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(false, "Malformed request body"));
    }

    @ExceptionHandler(LoaderValidationException.class)
    public ResponseEntity<ReloadResponse> handleLoaderValidation(LoaderValidationException ex) {
        return ResponseEntity.unprocessableEntity().body(new ReloadResponse(
                false,
                "Excel data validation failed.",
                ex.getReport().getViolations(),
                null
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(false, "Internal server error: " + ex.getMessage()));
    }
}
