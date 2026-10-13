package id.fatarc.portfolio.payflowreliability.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Checks the currency is a supported ISO 4217 code and the amount fits that currency's precision. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidMoney.Validator.class)
public @interface ValidMoney {
    String message() default "invalid amount for currency";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidMoney, MonetaryAmount> {
        /** Reports every problem once; a malformed currency code is left to the field's own pattern check. */
        @Override
        public boolean isValid(MonetaryAmount value, ConstraintValidatorContext context) {
            if (value == null || value.amount() == null || value.currency() == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            if (Money.isSupportedCurrency(value.currency())) {
                return Money.amountProblem(value.amount(), value.currency())
                        .map(problem -> reject(context, "amount", problem))
                        .orElse(true);
            }
            // Without a usable currency the precision cannot be checked, but the sign still can.
            boolean valid = true;
            if (value.currency().matches("[A-Z]{3}")) {
                valid = reject(context, "currency", "must be a supported ISO 4217 currency code");
            }
            if (value.amount().signum() <= 0) {
                valid = reject(context, "amount", "must be greater than zero");
            }
            return valid;
        }

        private static boolean reject(ConstraintValidatorContext context, String property, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(property).addConstraintViolation();
            return false;
        }
    }
}
