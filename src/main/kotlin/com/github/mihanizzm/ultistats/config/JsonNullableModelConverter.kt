package com.github.mihanizzm.ultistats.config

import com.fasterxml.jackson.databind.JavaType
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import org.openapitools.jackson.nullable.JsonNullable
import org.springframework.stereotype.Component
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

@Component
class JsonNullableModelConverter : ModelConverter {
    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>,
    ): Schema<*>? {
        val wrappedType = type.type?.jsonNullableValueType()
        if (wrappedType != null) {
            val valueType = AnnotatedType(wrappedType)
                .ctxAnnotations(type.ctxAnnotations)
                .jsonViewAnnotation(type.jsonViewAnnotation)
                .resolveAsRef(type.isResolveAsRef)
                .schemaProperty(type.isSchemaProperty)
                .propertyName(type.propertyName)
            return context.resolve(valueType)
        }
        return if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
    }

    private fun Type.jsonNullableValueType(): Type? = when (this) {
        is JavaType -> takeIf { rawClass == JsonNullable::class.java && containedTypeCount() == 1 }
            ?.containedType(0)
        is ParameterizedType -> takeIf { rawType == JsonNullable::class.java }
            ?.actualTypeArguments
            ?.single()
        else -> null
    }
}
