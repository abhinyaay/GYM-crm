package com.gymops.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Password policy: 10-72 characters (BCrypt truncates beyond 72 bytes), with upper case,
 * lower case and a digit.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {

    String message() default "must be 10-72 characters and contain upper case, lower case and a digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true; // @NotBlank handles presence
            }
            return value.length() >= 10 && value.length() <= 72
                    && value.chars().anyMatch(Character::isUpperCase)
                    && value.chars().anyMatch(Character::isLowerCase)
                    && value.chars().anyMatch(Character::isDigit);
        }
    }
}
