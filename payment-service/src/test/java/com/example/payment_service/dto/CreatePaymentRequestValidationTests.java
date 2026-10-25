package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.payment_service.entity.PaymentMethod;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePaymentRequestValidationTests {

	private static ValidatorFactory factory;

	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	@Test
	void validRequestHasNoViolations() {
		assertThat(validator.validate(request("49.90", "EUR", PaymentMethod.CARD))).isEmpty();
	}

	@Test
	void allFieldsAreRequired() {
		assertThat(invalidFields(new CreatePaymentRequest(null, null, null))).containsExactlyInAnyOrder("amount",
				"currency", "paymentMethod");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1.00", "10.001", "123456789012345678.00" })
	void rejectsInvalidAmounts(String amount) {
		assertThat(invalidFields(request(amount, "EUR", PaymentMethod.CARD))).containsExactly("amount");
	}

	@ParameterizedTest
	@ValueSource(strings = { "eur", "EU", "EURO", "E1R", "" })
	void rejectsInvalidCurrencies(String currency) {
		Set<ConstraintViolation<CreatePaymentRequest>> violations = validator
			.validate(request("10.00", currency, PaymentMethod.CARD));

		assertThat(violations).singleElement().satisfies((violation) -> {
			assertThat(violation.getPropertyPath()).hasToString("currency");
			assertThat(violation.getMessage()).isEqualTo("must be a 3-letter ISO currency code");
		});
	}

	private static CreatePaymentRequest request(String amount, String currency, PaymentMethod method) {
		return new CreatePaymentRequest(new BigDecimal(amount), currency, method);
	}

	private static List<String> invalidFields(CreatePaymentRequest request) {
		return validator.validate(request)
			.stream()
			.map(ConstraintViolation::getPropertyPath)
			.map(Object::toString)
			.toList();
	}

}
