package com.example.payment_service.dto;

import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreateMerchantRequestValidationTests {

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
		assertThat(validator.validate(new CreateMerchantRequest("Acme Store", "billing@acme.example", "+380501234567")))
			.isEmpty();
	}

	@Test
	void emailAndPhoneAreOptional() {
		assertThat(validator.validate(new CreateMerchantRequest("Acme Store", null, null))).isEmpty();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " " })
	void nameIsRequired(String name) {
		assertThat(invalidFields(new CreateMerchantRequest(name, null, null))).containsExactly("name");
	}

	@Test
	void nameIsAtMost255Characters() {
		assertThat(invalidFields(new CreateMerchantRequest("a".repeat(255), null, null))).isEmpty();
		assertThat(invalidFields(new CreateMerchantRequest("a".repeat(256), null, null))).containsExactly("name");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "not-an-email", "a@", "@acme.example" })
	void rejectsInvalidEmails(String email) {
		assertThat(invalidFields(new CreateMerchantRequest("Acme Store", email, null))).containsExactly("email");
	}

	@Test
	void emailIsAtMost255Characters() {
		String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".example";

		assertThat(email).hasSizeGreaterThan(255);
		assertThat(invalidFields(new CreateMerchantRequest("Acme Store", email, null))).containsExactly("email");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "380501234567", "+0501234567", "+38 050 123 4567", "+123456", "+1234567890123456",
			"+38050123456a" })
	void rejectsPhonesNotInE164Format(String phone) {
		assertThat(validator.validate(new CreateMerchantRequest("Acme Store", null, phone))).singleElement()
			.satisfies((violation) -> {
				assertThat(violation.getPropertyPath()).hasToString("phone");
				assertThat(violation.getMessage()).isEqualTo("must be in E.164 format, for example +380501234567");
			});
	}

	private static List<String> invalidFields(CreateMerchantRequest request) {
		return validator.validate(request)
			.stream()
			.map(ConstraintViolation::getPropertyPath)
			.map(Object::toString)
			.distinct()
			.toList();
	}

}
