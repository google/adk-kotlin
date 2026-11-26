/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.interop

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase
import com.fasterxml.jackson.databind.deser.CreatorProperty
import com.fasterxml.jackson.databind.deser.SettableBeanProperty
import com.fasterxml.jackson.databind.deser.ValueInstantiator
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer
import com.fasterxml.jackson.databind.deser.std.EnumDeserializer
import com.fasterxml.jackson.databind.introspect.AnnotatedMember
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.databind.util.BeanUtil
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.lang.reflect.AnnotatedArrayType
import java.lang.reflect.AnnotatedParameterizedType
import java.lang.reflect.AnnotatedType
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Infers the closed JSON schema of a Java port type: the keys [MAPPER] writes for an output, or
 * reads for an input; null for a type with no fixed shape, such as an abstract output, whose
 * getters do not fix the keys of the subclass value [MAPPER] writes, an input [MAPPER] reads as
 * another class through `@JsonDeserialize(as = ...)` or a builder, or one that contains itself. A
 * property takes null if it is marked nullable, has a reference type such as `AtomicReference`, or
 * has no fixed shape, unless an output's inclusion omits null, though NON_NULL still writes an
 * empty reference as null. An input requires an unaliased creator property if it is marked
 * required, or if it is neither marked nullable nor of a reference type and [MAPPER] writes it
 * under no `@JsonInclude` rule or never writes it. An input read through a delegating creator has
 * its delegate's schema.
 */
internal fun inferJavaPortSchema(type: JavaType, forOutput: Boolean): Schema? {
  val inference = JavaPortSchemas(forOutput)
  return inference.describe(type).takeUnless { inference.recursive }
}

// Jackson writes a char[] as a string, like a byte[] in base64.
private val STRINGS =
  listOf(String::class, CharSequence::class, Char::class, ByteArray::class, CharArray::class).map {
    it.javaObjectType
  }
private val INTEGERS =
  listOf(Int::class, Long::class, Short::class, Byte::class, BigInteger::class).map {
    it.javaObjectType
  }
private val NUMBERS =
  listOf(Float::class, Double::class, BigDecimal::class, Number::class).map { it.javaObjectType }
private val CONFIG = MAPPER.serializationConfig
private val ANNOTATIONS = CONFIG.annotationIntrospector

private class JavaPortSchemas(private val forOutput: Boolean) {
  /** The records and POJOs being described, to find a type that contains itself. */
  private val enclosing = mutableSetOf<JavaType>()

  var recursive = false
    private set

  fun describe(type: JavaType, annotatedTypes: List<AnnotatedType> = emptyList()): Schema? {
    val raw = type.rawClass.kotlin.javaObjectType
    return when {
      raw in STRINGS -> Schema(type = Type.STRING)
      raw == Boolean::class.javaObjectType -> Schema(type = Type.BOOLEAN)
      raw in INTEGERS -> Schema(type = Type.INTEGER)
      raw in NUMBERS -> Schema(type = Type.NUMBER)
      raw.isEnum -> describeEnum(type)
      type.isReferenceType ->
        describe(type.contentType, elementAnnotatedTypes(annotatedTypes))?.copy(nullable = true)
      type.isArrayType || type.isCollectionLikeType -> {
        val elements = elementAnnotatedTypes(annotatedTypes)
        val items =
          describe(type.contentType, elements)?.let {
            if (!type.contentType.isPrimitive && hasNullableTypeAnnotation(elements)) {
              it.copy(nullable = true)
            } else {
              it
            }
          }
        Schema(type = Type.ARRAY, items = items)
      }
      type.isMapLikeType -> Schema(type = Type.OBJECT)
      else -> describeObject(type)
    }
  }

  /**
   * Returns a string enum of the values [MAPPER] writes, or null if one is not text or an input
   * uses a custom or `@JsonCreator` deserializer.
   */
  private fun describeEnum(type: JavaType): Schema? {
    if (!forOutput && !usesDefaultEnumDeserializer(type)) return null
    val values =
      runCatching { type.rawClass.enumConstants.map { MAPPER.valueToTree<JsonNode>(it) } }
        .getOrNull()
    if (values == null || !values.all { it.isTextual }) return null
    return Schema(type = Type.STRING, enum = values.map { it.textValue() })
  }

  /** Returns the closed schema of a record or POJO, or null if its keys are not fixed. */
  private fun describeObject(type: JavaType): Schema? {
    // Polymorphic types add subtype keys; an abstract type's getters do not fix its value's keys.
    if (isPolymorphic(type) || !hasFixedKeys(type) || (forOutput && type.isAbstract)) return null
    val deserializer = if (forOutput) null else findBeanDeserializer(type)
    val delegate = if (forOutput) null else delegateType(deserializer)
    // A @JsonDeserialize(as = ...) or builder input reads another class, whose keys may differ.
    if (delegate == null && deserializer != null && deserializer.valueType != type) return null
    val beanProperties =
      if (delegate != null) emptyList() else beanProperties(type, deserializer) ?: return null
    if (!enclosing.add(type)) {
      recursive = true
      return null
    }
    try {
      if (delegate != null) return describe(delegate)
      val description = CONFIG.introspect(type)
      // Writing and reading may see different accessors; a `@Nullable` on either counts.
      val readView = MAPPER.deserializationConfig.introspect(type)
      val properties = mutableMapOf<String, Schema>()
      val required = mutableListOf<String>()
      for (property in beanProperties) {
        // Use `@JsonSerialize(as/contentAs)` only for a supertype; a subtype adds unread keys.
        var propertyType =
          (property as? BeanPropertyWriter)?.serializationType?.takeIf {
            generalizes(it, property.type)
          } ?: property.type
        val members = propertyMembers(description, readView, property)
        var annotatedTypes = members.mapNotNull(::annotatedType)
        var nullable =
          !propertyType.isPrimitive &&
            (propertyType.isReferenceType || hasNullableAnnotation(members))
        val include = if (forOutput) inclusion(description, property) else Include.ALWAYS
        val omitted = isOmitted(include, propertyType, nullable)
        if (omitted && include != Include.CUSTOM) {
          // Of the inclusions that omit null, only NON_NULL writes an empty reference as null.
          nullable = include == Include.NON_NULL && propertyType.isReferenceType
        }
        if (hasElementTypeId(property)) {
          propertyType = propertyType.withContentType(TypeFactory.unknownType())
        }
        if (propertyType.isReferenceType) {
          propertyType = propertyType.contentType
          annotatedTypes = elementAnnotatedTypes(annotatedTypes)
        }
        val schema = if (hasOwnForm(property)) null else describe(propertyType, annotatedTypes)
        // A property of no fixed shape takes any value.
        val propertySchema =
          when {
            schema == null -> Schema()
            nullable -> schema.copy(nullable = true)
            else -> schema
          }
        properties[property.name] = propertySchema
        // Any of an aliased property's names may carry it, so none of them is required.
        val aliases = if (forOutput) emptyList() else property.findAliases(CONFIG)
        for (alias in aliases) properties[alias.simpleName] = propertySchema
        val isRequired =
          if (forOutput) {
            !omitted
          } else {
            aliases.isEmpty() &&
              property is CreatorProperty &&
              (property.isRequired || (!nullable && !mayOmit(description, property)))
          }
        if (isRequired) required += property.name
      }
      return Schema(
        type = Type.OBJECT,
        properties = properties,
        required = required.ifEmpty { null },
        additionalProperties = false,
      )
    } finally {
      enclosing.remove(type)
    }
  }

  /** Returns the properties an output writes or an input reads, or null if [type] is no bean. */
  private fun beanProperties(
    type: JavaType,
    deserializer: BeanDeserializerBase?,
  ): List<BeanProperty>? {
    if (forOutput) {
      val serializer =
        runCatching { MAPPER.serializerProviderInstance.findValueSerializer(type) }.getOrNull()
          as? BeanSerializerBase ?: return null
      return serializer.properties().asSequence().toList()
    }
    val bean = deserializer ?: return null
    val creator = bean.valueInstantiator
    // A scalar or array creator, or a delegate of its own codec, reads another JSON form too.
    if (creator.canCreateUsingDelegate() || nonObjectForms(creator).isNotEmpty()) return null
    if (!creator.canCreateUsingDefault() && !creator.canCreateFromObjectWith()) return null
    val declared = CONFIG.introspect(type).findProperties().map { it.name }
    // Jackson 2.18 leaves a factory's CreatorProperty out of properties(), so keep it first.
    val creatorArguments = creator.getFromObjectArguments(MAPPER.deserializationConfig).orEmpty()
    // Jackson keeps an ignored creator parameter, to pass it null.
    return (creatorArguments.asSequence() + bean.properties().asSequence())
      .distinctBy { it.name }
      .filterNot { it.isIgnorable }
      .sortedBy { declared.indexOf(it.name) }
      .toList()
  }

  /** Whether a property's own codec, converter, type id, object id or shape may change its form. */
  private fun hasOwnForm(property: BeanProperty): Boolean {
    if (
      (property as? BeanPropertyWriter)?.typeSerializer != null ||
        (property as? SettableBeanProperty)?.valueTypeDeserializer != null
    ) {
      return true
    }
    val member = property.member ?: return false
    val ownCodec =
      if (forOutput) {
        ANNOTATIONS.findSerializer(member) != null ||
          ANNOTATIONS.findContentSerializer(member) != null ||
          ANNOTATIONS.findSerializationConverter(member) != null ||
          ANNOTATIONS.findSerializationContentConverter(member) != null
      } else {
        ANNOTATIONS.findDeserializer(member) != null ||
          ANNOTATIONS.findContentDeserializer(member) != null ||
          ANNOTATIONS.findDeserializationConverter(member) != null ||
          ANNOTATIONS.findDeserializationContentConverter(member) != null
      }
    return ownCodec ||
      ANNOTATIONS.findObjectIdInfo(member) != null ||
      ANNOTATIONS.findFormat(member)?.hasShape() == true
  }
}

/** Whether [MAPPER] reads [type] with Jackson's standard enum reader (no `@JsonCreator`). */
private fun usesDefaultEnumDeserializer(type: JavaType): Boolean {
  var deserializer: JsonDeserializer<*>? =
    try {
      deserializationContext().findRootValueDeserializer(type)
    } catch (_: Exception) {
      null
    }
  while (deserializer is DelegatingDeserializer) {
    deserializer = deserializer.delegatee
  }
  return deserializer is EnumDeserializer
}

/** Returns Jackson's bean deserializer for [type], or null if [type] is no bean. */
private fun findBeanDeserializer(type: JavaType): BeanDeserializerBase? =
  runCatching { deserializationContext().findRootValueDeserializer(type) }.getOrNull()
    as? BeanDeserializerBase

/** Returns the type Jackson reads all of [deserializer]'s type as, if its delegate alone does. */
private fun delegateType(deserializer: BeanDeserializerBase?): JavaType? {
  val creator = deserializer?.valueInstantiator ?: return null
  if (!creator.canCreateUsingDelegate()) return null
  val delegate = creator.getDelegateType(MAPPER.deserializationConfig)
  // A delegate's own deserializer may read any form; a BigInteger delegate is a scalar creator too.
  return delegate.takeIf {
    it.getValueHandler<Any>() == null && (nonObjectForms(creator) - it.rawClass).isEmpty()
  }
}

/**
 * Returns the Java type of each JSON form but an object that [creator] reads; a List for arrays.
 */
private fun nonObjectForms(creator: ValueInstantiator): Set<Class<*>> =
  setOfNotNull(
    String::class.java.takeIf { creator.canCreateFromString() },
    Int::class.java.takeIf { creator.canCreateFromInt() },
    Long::class.java.takeIf { creator.canCreateFromLong() },
    BigInteger::class.java.takeIf { creator.canCreateFromBigInteger() },
    Double::class.java.takeIf { creator.canCreateFromDouble() },
    BigDecimal::class.java.takeIf { creator.canCreateFromBigDecimal() },
    Boolean::class.java.takeIf { creator.canCreateFromBoolean() },
    List::class.java.takeIf { creator.canCreateUsingArrayDelegate() },
  )

/** Whether [general] is [type] or a supertype of it, and its content type is too. */
private fun generalizes(general: JavaType, type: JavaType): Boolean =
  general.rawClass.isAssignableFrom(type.rawClass) &&
    (general.contentType == null ||
      type.contentType == null ||
      generalizes(general.contentType, type.contentType))

/** Whether [MAPPER] may leave out a property whose inclusion is [include]. */
private fun isOmitted(include: Include, type: JavaType, nullable: Boolean): Boolean {
  val raw = type.rawClass
  val emptiable =
    nullable ||
      type.isContainerType ||
      raw == Any::class.java ||
      CharSequence::class.java.isAssignableFrom(raw) ||
      Iterable::class.java.isAssignableFrom(raw) ||
      JsonNode::class.java.isAssignableFrom(raw)
  return when (include) {
    Include.NON_NULL,
    Include.NON_ABSENT -> nullable
    Include.NON_EMPTY -> emptiable
    Include.NON_DEFAULT -> emptiable || BeanUtil.getDefaultValue(type) != null
    Include.CUSTOM -> true
    else -> false
  }
}

/** Returns how [MAPPER] includes [property] on write: its own, else its class's or mapper's. */
private fun inclusion(description: BeanDescription, property: BeanProperty): Include {
  val include =
    description.findPropertyInclusion(CONFIG.getDefaultPropertyInclusion(description.beanClass))
  val own =
    property.member?.let { ANNOTATIONS.findPropertyInclusion(it) } ?: JsonInclude.Value.empty()
  val value = include.withOverrides(own).valueInclusion
  // A class-level NON_DEFAULT compares each value with a default instance, so may omit any value.
  val classDefault =
    include.valueInclusion == Include.NON_DEFAULT && description.findDefaultConstructor() != null
  return if (value == Include.NON_DEFAULT && classDefault) Include.CUSTOM else value
}

/**
 * Whether a value may lack [property]: [MAPPER] writes it under a `@JsonInclude` rule, or through a
 * serializer whose keys it cannot see. A property the bean serializer never writes, such as one
 * `@JsonIgnoreProperties(allowSetters = true)` names, still counts, as its reader would get null.
 */
private fun mayOmit(description: BeanDescription, property: BeanProperty): Boolean {
  val serializer =
    runCatching { MAPPER.serializerProviderInstance.findValueSerializer(description.type) }
      .getOrNull() as? BeanSerializerBase ?: return true
  val written =
    serializer.properties().asSequence().firstOrNull { it.name == property.name } ?: return false
  val include = inclusion(description, written)
  return include != Include.ALWAYS && include != Include.USE_DEFAULTS
}

/**
 * Whether [type]'s keys are its property names: no any-getter or any-setter, object id, unwrapped
 * or external-type-id property, or shape other than OBJECT.
 */
private fun hasFixedKeys(type: JavaType): Boolean =
  try {
    val description = CONFIG.introspect(type)
    // The serialization view drops a WRITE_ONLY field, so check the reading view's members too.
    val readView = MAPPER.deserializationConfig.introspect(type)
    val format = description.findExpectedFormat()
    description.findAnyGetter() == null &&
      description.findAnySetterAccessor() == null &&
      // findAnySetterAccessor() checks only methods and fields, not @JsonCreator parameters.
      (description.constructors + description.factoryMethods).none { creator ->
        (0 until creator.parameterCount).any {
          ANNOTATIONS.hasAnySetter(creator.getParameter(it)) == true
        }
      } &&
      description.objectIdInfo == null &&
      (!format.hasShape() || format.shape == JsonFormat.Shape.OBJECT) &&
      (description.findProperties() + readView.findProperties()).flatMap(::members).none {
        ANNOTATIONS.findUnwrappingNameTransformer(it) != null ||
          ANNOTATIONS.findPolymorphicTypeInfo(CONFIG, it)?.inclusionType ==
            JsonTypeInfo.As.EXTERNAL_PROPERTY
      }
  } catch (_: RuntimeException) {
    // Jackson rejects a malformed or second any-getter or any-setter.
    false
  }

/** Whether the `@JsonTypeInfo` of a container property puts a type id on each element. */
private fun hasElementTypeId(property: BeanProperty): Boolean {
  val member = property.member ?: return false
  val type = property.type
  return (type.isContainerType || type.isReferenceType) &&
    ANNOTATIONS.findPropertyContentTypeResolver(CONFIG, member, type) != null
}

/** Returns every Jackson member for [property] across serialization and deserialization views. */
private fun propertyMembers(
  description: BeanDescription,
  readView: BeanDescription,
  property: BeanProperty,
): List<AnnotatedMember> =
  listOfNotNull(property.member) +
    (description.findProperties() + readView.findProperties())
      .filter { it.name == property.name }
      .flatMap(::members)

/** Whether any of [members] carries a `@Nullable` of any package, including type annotations. */
private fun hasNullableAnnotation(members: List<AnnotatedMember>): Boolean =
  members
    .flatMap { member ->
      member.allAnnotations?.annotations()?.toList().orEmpty() +
        annotatedType(member)?.annotations?.toList().orEmpty()
    }
    .any { it.annotationClass.java.simpleName == "Nullable" }

/** Whether any of [types] carries a type-use `@Nullable` of any package. */
private fun hasNullableTypeAnnotation(types: List<AnnotatedType>): Boolean = types.any { type ->
  type.annotations.any { it.annotationClass.java.simpleName == "Nullable" }
}

/** Returns the element or single type-argument [AnnotatedType] of each container in [types]. */
private fun elementAnnotatedTypes(types: List<AnnotatedType>): List<AnnotatedType> =
  types.mapNotNull { type ->
    when (type) {
      is AnnotatedArrayType -> type.annotatedGenericComponentType
      is AnnotatedParameterizedType -> type.annotatedActualTypeArguments.singleOrNull()
      else -> null
    }
  }

/** Returns the field, creator parameter, getter and setter of [definition] that it has. */
private fun members(definition: BeanPropertyDefinition): List<AnnotatedMember> =
  listOfNotNull(
    definition.field,
    definition.constructorParameter,
    // Jackson rejects overloaded getters or setters it cannot choose between.
    runCatching { definition.getter }.getOrNull(),
    runCatching { definition.setter }.getOrNull(),
  )

/**
 * Returns the [AnnotatedType] of a Jackson field, getter, setter or parameter, where a type-use
 * `@Nullable` such as JSpecify's sits (on both the member type and its type arguments).
 */
private fun annotatedType(member: AnnotatedMember): AnnotatedType? {
  val element = member.member
  return when {
    // An inner class's constructor can list fewer annotated types than parameters.
    member is AnnotatedParameter ->
      (member.owner.member as? Executable)?.annotatedParameterTypes?.getOrNull(member.index)
    element is Method && element.parameterCount == 1 -> element.annotatedParameterTypes[0]
    element is Method -> element.annotatedReturnType
    element is Field -> element.annotatedType
    else -> null
  }
}
