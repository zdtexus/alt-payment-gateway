package com.example.payment_service.error;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
		String message = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> error.getField() + ": " + error.getDefaultMessage())
			.sorted()
			.collect(Collectors.joining("; "));
		return validationFailed(message, request);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorResponse> handleInvalidParameter(HandlerMethodValidationException ex,
			HttpServletRequest request) {
		String message = ex.getParameterValidationResults()
			.stream()
			.flatMap((result) -> result.getResolvableErrors()
				.stream()
				.map((error) -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
			.sorted()
			.collect(Collectors.joining("; "));
		return validationFailed(message, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return validationFailed("Malformed request body", request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
			HttpServletRequest request) {
		return validationFailed(ex.getName() + ": invalid value", request);
	}

	@ExceptionHandler(InvalidApiKeyException.class)
	ResponseEntity<ErrorResponse> handleInvalidApiKey(InvalidApiKeyException ex, HttpServletRequest request) {
		return error(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", ex.getMessage(), request);
	}

	@ExceptionHandler(MerchantSuspendedException.class)
	ResponseEntity<ErrorResponse> handleMerchantSuspended(MerchantSuspendedException ex, HttpServletRequest request) {
		return error(HttpStatus.FORBIDDEN, "MERCHANT_SUSPENDED", ex.getMessage(), request);
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request);
	}

	@ExceptionHandler(PaymentRuleViolationException.class)
	ResponseEntity<ErrorResponse> handlePaymentRuleViolation(PaymentRuleViolationException ex,
			HttpServletRequest request) {
		return error(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_RULE_VIOLATION", ex.getMessage(), request);
	}

	private static ResponseEntity<ErrorResponse> validationFailed(String message, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status).body(ErrorResponse.of(status, error, message, request.getRequestURI()));
	}

}
