/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.diagnostics.ConeIntermediateDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.java.enhancement.readOnlyToMutable
import org.jetbrains.kotlin.fir.resolve.toFirRegularClass
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.jvm.FirJavaTypeRef
import org.jetbrains.kotlin.fir.types.jvm.buildJavaTypeRef
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly

private fun ClassId.toLookupTag(): ConeClassLikeLookupTag =
    ConeClassLikeLookupTagImpl(this)

private fun ClassId.toConeFlexibleType(
    typeArguments: Array<out ConeTypeProjection>,
    typeArgumentsForUpper: Array<out ConeTypeProjection>,
    attributes: ConeAttributes
) = toLookupTag().run {
    ConeFlexibleType(
        constructClassType(typeArguments, isNullable = false, attributes),
        constructClassType(typeArgumentsForUpper, isNullable = true, attributes)
    )
}

internal enum class FirJavaTypeConversionMode {
    DEFAULT, ANNOTATION_MEMBER, SUPERTYPE, TYPE_PARAMETER_BOUND
}

internal fun FirTypeRef.resolveIfJavaType(
    session: FirSession, javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode = FirJavaTypeConversionMode.DEFAULT
): FirTypeRef = when (this) {
    is FirResolvedTypeRef -> this
    is FirJavaTypeRef -> type.toFirResolvedTypeRef(session, javaTypeParameterStack, mode)
    else -> this
}

internal fun FirTypeRef.toConeKotlinTypeProbablyFlexible(
    session: FirSession, javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode = FirJavaTypeConversionMode.DEFAULT
): ConeKotlinType =
    (resolveIfJavaType(session, javaTypeParameterStack, mode) as? FirResolvedTypeRef)?.type
        ?: ConeKotlinErrorType(ConeSimpleDiagnostic("Type reference in Java not resolved: ${this::class.java}", DiagnosticKind.Java))

internal fun JavaType.toFirJavaTypeRef(session: FirSession, javaTypeParameterStack: JavaTypeParameterStack): FirJavaTypeRef {
    return buildJavaTypeRef {
        annotationBuilder = { convertAnnotationsToFir(session, javaTypeParameterStack) }
        type = this@toFirJavaTypeRef
    }
}

internal fun JavaType?.toFirResolvedTypeRef(
    session: FirSession, javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode = FirJavaTypeConversionMode.DEFAULT
): FirResolvedTypeRef {
    return buildResolvedTypeRef {
        type = toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, mode)
            .let { if (mode == FirJavaTypeConversionMode.SUPERTYPE) it.lowerBoundIfFlexible() else it }
        annotations += type.attributes.customAnnotations
    }
}

private fun JavaType?.toConeKotlinTypeWithoutEnhancement(
    session: FirSession, javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode
): ConeKotlinType {
    val attributes = if (this != null && annotations.isNotEmpty())
        ConeAttributes.create(listOf(CustomAnnotationTypeAttribute(convertAnnotationsToFir(session, javaTypeParameterStack))))
    else
        ConeAttributes.Empty

    return when (this) {
        is JavaClassifierType ->
            toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, mode, attributes)
        is JavaPrimitiveType -> {
            val primitiveType = type
            val kotlinPrimitiveName = when (val javaName = primitiveType?.typeName?.asString()) {
                null -> "Unit"
                else -> javaName.capitalizeAsciiOnly()
            }

            val classId = StandardClassIds.byName(kotlinPrimitiveName)
            classId.constructClassLikeType(emptyArray(), isNullable = false, attributes)
        }
        is JavaArrayType ->
            toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, mode, attributes)
        is JavaWildcardType ->
            bound?.toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, mode)
        null -> null
        else -> error("Strange JavaType: ${this::class.java}")
    } ?: StandardClassIds.Any.toConeFlexibleType(emptyArray(), emptyArray(), attributes = attributes)
}

private fun JavaArrayType.toConeKotlinTypeWithoutEnhancement(
    session: FirSession,
    javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode,
    attributes: ConeAttributes
): ConeKotlinType {
    val componentType = componentType
    return if (componentType !is JavaPrimitiveType) {
        val classId = StandardClassIds.Array
        val argumentType = componentType.toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, mode)
        if (mode == FirJavaTypeConversionMode.ANNOTATION_MEMBER) {
            classId.constructClassLikeType(arrayOf(argumentType), isNullable = false, attributes = attributes)
        } else {
            classId.toConeFlexibleType(
                arrayOf(argumentType),
                typeArgumentsForUpper = arrayOf(ConeKotlinTypeProjectionOut(argumentType)),
                attributes = attributes
            )
        }
    } else {
        val javaComponentName = componentType.type?.typeName?.asString()?.capitalizeAsciiOnly() ?: error("Array of voids")
        val classId = StandardClassIds.byName(javaComponentName + "Array")

        if (mode == FirJavaTypeConversionMode.ANNOTATION_MEMBER) {
            classId.constructClassLikeType(emptyArray(), isNullable = false, attributes = attributes)
        } else {
            classId.toConeFlexibleType(emptyArray(), emptyArray(), attributes = attributes)
        }
    }
}

private fun JavaClassifierType.toConeKotlinTypeWithoutEnhancement(
    session: FirSession,
    javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode,
    attributes: ConeAttributes
): ConeKotlinType {
    val lowerBound = toConeKotlinTypeForFlexibleBound(session, javaTypeParameterStack, mode, attributes)
    if (mode == FirJavaTypeConversionMode.ANNOTATION_MEMBER) {
        return lowerBound
    }
    val upperBound = toConeKotlinTypeForFlexibleBound(session, javaTypeParameterStack, mode, attributes, lowerBound)
    return if (isRaw)
        ConeRawType(lowerBound, upperBound)
    else
        ConeFlexibleType(lowerBound, upperBound)
}

private fun JavaClassifierType.toConeKotlinTypeForFlexibleBound(
    session: FirSession,
    javaTypeParameterStack: JavaTypeParameterStack,
    mode: FirJavaTypeConversionMode,
    attributes: ConeAttributes,
    lowerBound: ConeLookupTagBasedType? = null
): ConeLookupTagBasedType {
    return when (val classifier = classifier) {
        is JavaClass -> {
            var classId = if (mode == FirJavaTypeConversionMode.ANNOTATION_MEMBER) {
                JavaToKotlinClassMap.mapJavaToKotlinIncludingClassMapping(classifier.fqName!!)
            } else {
                JavaToKotlinClassMap.mapJavaToKotlin(classifier.fqName!!)
            } ?: classifier.classId!!

            if (lowerBound == null || argumentsMakeSenseOnlyForMutableContainer(classId, session)) {
                classId = classId.readOnlyToMutable() ?: classId
            }

            val lookupTag = ConeClassLikeLookupTagImpl(classId)
            // When converting type parameter bounds we should not attempt to load any classes, as this may trigger
            // enhancement of type parameter bounds on some other class that depends on this one. Also, in case of raw
            // types specifically there could be an infinite recursion on the type parameter itself.
            val typeParameters = lookupTag.takeIf { mode != FirJavaTypeConversionMode.TYPE_PARAMETER_BOUND }
                ?.toFirRegularClass(session)?.typeParameters
            val mappedTypeArguments = when {
                isRaw ->
                    // Given `C<T : X>`, `C` -> `C<X>..C<*>?`.
                    typeParameters.takeIf { lowerBound == null }?.eraseToUpperBounds(session)
                        ?: Array(classifier.typeParameters.size) { ConeStarProjection }
                lookupTag != lowerBound?.lookupTag ->
                    Array(typeArguments.size) { index ->
                        val argument = typeArguments[index]
                        val parameter = typeParameters?.getOrNull(index)?.symbol?.fir
                        argument.toConeProjectionWithoutEnhancement(session, javaTypeParameterStack, parameter, mode)
                    }
                else -> lowerBound.typeArguments
            }

            lookupTag.constructClassType(mappedTypeArguments, isNullable = lowerBound != null, attributes)
        }
        is JavaTypeParameter -> {
            val symbol = javaTypeParameterStack[classifier]
            ConeTypeParameterTypeImpl(symbol.toLookupTag(), isNullable = lowerBound != null, attributes)
        }
        null -> {
            val classId = ClassId.topLevel(FqName(this.classifierQualifiedName))
            classId.constructClassLikeType(emptyArray(), isNullable = lowerBound != null, attributes)
        }
        else -> ConeKotlinErrorType(ConeSimpleDiagnostic("Unexpected classifier: $classifier", DiagnosticKind.Java))
    }
}

// Returns true for covariant read-only container that has mutable pair with invariant parameter
// List<in A> does not make sense, but MutableList<in A> does
// Same for Map<K, in V>
// But both Iterable<in A>, MutableIterable<in A> don't make sense as they are covariant, so return false
private fun JavaClassifierType.argumentsMakeSenseOnlyForMutableContainer(
    classId: ClassId,
    session: FirSession,
): Boolean {
    if (!JavaToKotlinClassMap.isReadOnly(classId.asSingleFqName().toUnsafe())) return false
    val mutableClassId = classId.readOnlyToMutable() ?: return false

    if (!typeArguments.lastOrNull().isSuperWildcard()) return false
    val mutableLastParameterVariance =
        mutableClassId.toLookupTag().toFirRegularClass(session)?.typeParameters?.lastOrNull()?.symbol?.fir?.variance
            ?: return false

    return mutableLastParameterVariance != Variance.OUT_VARIANCE
}

private fun List<FirTypeParameterRef>.eraseToUpperBounds(session: FirSession): Array<ConeTypeProjection> {
    val cache = mutableMapOf<FirTypeParameter, ConeKotlinType>()
    return Array(size) { index -> this[index].symbol.fir.eraseToUpperBound(session, cache) }
}

private fun FirTypeParameter.eraseToUpperBound(session: FirSession, cache: MutableMap<FirTypeParameter, ConeKotlinType>): ConeKotlinType {
    return cache.getOrPut(this) {
        // Mark to avoid loops.
        cache[this] = ConeKotlinErrorType(ConeIntermediateDiagnostic("self-recursive type parameter $name"))
        // We can assume that Java type parameter bounds are already converted.
        bounds.first().coneType.eraseAsUpperBound(session, cache)
    }
}

private fun ConeKotlinType.eraseAsUpperBound(session: FirSession, cache: MutableMap<FirTypeParameter, ConeKotlinType>): ConeKotlinType =
    when (this) {
        is ConeClassLikeType ->
            withArguments(typeArguments.map { ConeStarProjection }.toTypedArray())
        is ConeFlexibleType ->
            // If one bound is a type parameter, the other is probably the same type parameter,
            // so there is no exponential complexity here due to cache lookups.
            coneFlexibleOrSimpleType(
                session.typeContext,
                lowerBound.eraseAsUpperBound(session, cache),
                upperBound.eraseAsUpperBound(session, cache)
            )
        is ConeTypeParameterType ->
            lookupTag.typeParameterSymbol.fir.eraseToUpperBound(session, cache).let {
                if (isNullable) it.withNullability(nullability, session.typeContext) else it
            }
        is ConeDefinitelyNotNullType ->
            original.eraseAsUpperBound(session, cache).makeConeTypeDefinitelyNotNullOrNotNull(session.typeContext)
        else -> error("unexpected Java type parameter upper bound kind: $this")
    }

private fun JavaType?.toConeProjectionWithoutEnhancement(
    session: FirSession,
    javaTypeParameterStack: JavaTypeParameterStack,
    boundTypeParameter: FirTypeParameter?,
    mode: FirJavaTypeConversionMode
): ConeTypeProjection {
    // TODO: check this
    val newMode = mode.takeIf { it == FirJavaTypeConversionMode.SUPERTYPE } ?: FirJavaTypeConversionMode.DEFAULT
    return when (this) {
        null -> ConeStarProjection
        is JavaWildcardType -> {
            val bound = this.bound
            val argumentVariance = if (isExtends) Variance.OUT_VARIANCE else Variance.IN_VARIANCE
            val parameterVariance = boundTypeParameter?.variance ?: Variance.INVARIANT
            if (bound == null || parameterVariance != Variance.INVARIANT && parameterVariance != argumentVariance) {
                ConeStarProjection
            } else {
                val boundType = bound.toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, newMode)
                if (argumentVariance == Variance.OUT_VARIANCE) {
                    ConeKotlinTypeProjectionOut(boundType)
                } else {
                    ConeKotlinTypeProjectionIn(boundType)
                }
            }
        }
        else -> toConeKotlinTypeWithoutEnhancement(session, javaTypeParameterStack, newMode)
    }
}
