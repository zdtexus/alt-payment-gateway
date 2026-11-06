package com.example.payment_service.error;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
				.map((error) -> errorSource(result.getMethodParameter(), error) + ": " + error.getDefaultMessage()))
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

	@ExceptionHandler(InvalidAdminKeyException.class)
	ResponseEntity<ErrorResponse> handleInvalidAdminKey(InvalidAdminKeyException ex, HttpServletRequest request) {
		return error(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_KEY", ex.getMessage(), request);
	}

	@ExceptionHandler(MerchantSuspendedException.class)
	ResponseEntity<ErrorResponse> handleMerchantSuspended(MerchantSuspendedException ex, HttpServletRequest request) {
		return error(HttpStatus.FORBIDDEN, "MERCHANT_SUSPENDED", ex.getMessage(), request);
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request);
	}

	@ExceptionHandler(MerchantNotFoundException.class)
	ResponseEntity<ErrorResponse> handleMerchantNotFound(MerchantNotFoundException ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", ex.getMessage(), request);
	}

	@ExceptionHandler(PaymentRuleViolationException.class)
	ResponseEntity<ErrorResponse> handlePaymentRuleViolation(PaymentRuleViolationException ex,
			HttpServletRequest request) {
		return error(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_RULE_VIOLATION", ex.getMessage(), request);
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex,
			HttpServletRequest request) {
		return error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage(), request);
	}

	@ExceptionHandler(WebhookNotAcceptedException.class)
	ResponseEntity<ErrorResponse> handleWebhookNotAccepted(WebhookNotAcceptedException ex,
			HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", ex.getMessage(), request);
	}

	@ExceptionHandler(InvalidWebhookSignatureException.class)
	ResponseEntity<ErrorResponse> handleInvalidWebhookSignature(InvalidWebhookSignatureException ex,
			HttpServletRequest request) {
		return error(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", ex.getMessage(), request);
	}

	@ExceptionHandler(InvalidWebhookException.class)
	ResponseEntity<ErrorResponse> handleInvalidWebhook(InvalidWebhookException ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK", ex.getMessage(), request);
	}

	@ExceptionHandler(WebhookPaymentNotFoundException.class)
	ResponseEntity<ErrorResponse> handleWebhookPaymentNotFound(WebhookPaymentNotFoundException ex,
			HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleOtherException(Exception ex, HttpServletRequest request) {
		if (ex instanceof org.springframework.web.ErrorResponse framework) {
			return frameworkError(ex, framework, request);
		}
		log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
		return statusError(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(), request);
	}

	private static ResponseEntity<ErrorResponse> frameworkError(Exception ex,
			org.springframework.web.ErrorResponse framework, HttpServletRequest request) {
		HttpStatus resolved = HttpStatus.resolve(framework.getStatusCode().value());
		HttpStatus status = (resolved != null) ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
		if (status.is5xxServerError()) {
			log.error("Request failed on {} {}", request.getMethod(), request.getRequestURI(), ex);
		}
		return statusError(status, framework.getHeaders(), request);
	}

	private static ResponseEntity<ErrorResponse> statusError(HttpStatus status, HttpHeaders headers,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
			.headers(headers)
			.body(ErrorResponse.of(status, status.name(), status.getReasonPhrase(), request.getRequestURI()));
	}

	private static String errorSource(MethodParameter parameter, MessageSourceResolvable error) {
		if (error instanceof FieldError fieldError) {
			return fieldError.getField();
		}
		RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
		if (header != null && !header.name().isEmpty()) {
			return header.name();
		}
		return parameter.getParameterName();
	}

	private static ResponseEntity<ErrorResponse> validationFailed(String message, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status).body(ErrorResponse.of(status, error, message, request.getRequestURI()));
	}

}
