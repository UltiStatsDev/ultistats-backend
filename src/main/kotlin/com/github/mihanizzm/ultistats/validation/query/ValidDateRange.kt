package com.github.mihanizzm.ultistats.validation.query

import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [DateRangeValidator::class])
annotation class ValidDateRange(
    val message: String = "dateFrom must be before or equal to dateTo",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class DateRangeValidator : ConstraintValidator<ValidDateRange, MatchFilterRequest> {
    override fun isValid(value: MatchFilterRequest?, context: ConstraintValidatorContext): Boolean {
        val from = value?.dateFrom ?: return true
        val to = value.dateTo ?: return true
        if (from <= to) return true
        context.disableDefaultConstraintViolation()
        context.buildConstraintViolationWithTemplate(context.defaultConstraintMessageTemplate)
            .addPropertyNode("dateTo").addConstraintViolation()
        return false
    }
}
