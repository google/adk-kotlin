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

@file:OptIn(AdkJavaInteropApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Requiredness
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

/**
 * Builds a [BaseTool] from a [Tool]-annotated Java method, the reflective counterpart of the
 * engine's KSP `@Tool` path (which needs the Kotlin compiler and so is closed to a javac-only
 * build). It reads the same [Tool]/[Param] annotations, relying on [Param.name]/[Param.required]
 * because Java retains neither parameter names nor nullability. JVM-only and intended for
 * javac-only modules; prefer the KSP `@Tool` path whenever the code is built with the Kotlin
 * compiler, which processes `.java` sources too.
 */
@AdkJavaInteropApi
object ReflectiveTools {

  /** Builds a [BaseTool] from the uniquely-named [Tool] method [methodName] on [instance]. */
  @JvmStatic
  fun fromMethod(instance: Any, methodName: String): BaseTool {
    // Skip compiler-generated bridge/synthetic overloads so covariant returns don't look
    // overloaded.
    val candidates =
      instance.javaClass.methods.filter { it.name == methodName && !it.isSynthetic && !it.isBridge }
    require(candidates.isNotEmpty()) {
      "No method named '$methodName' on ${instance.javaClass.name}."
    }
    require(candidates.size == 1) {
      "Method '$methodName' on ${instance.javaClass.name} is overloaded; tool methods must be" +
        " unambiguous."
    }
    return fromMethod(instance, candidates.single())
  }

  /** Builds a [BaseTool] from an explicit [method] on [instance]. */
  @JvmStatic
  fun fromMethod(instance: Any, method: Method): BaseTool {
    val annotation =
      requireNotNull(method.getAnnotation(Tool::class.java)) {
        "Method ${method.name} is not annotated with @Tool."
      }
    require(annotation.description.isNotEmpty()) {
      "@Tool on ${method.name} needs a description; reflection cannot read the KDoc that the KSP" +
        " path falls back to."
    }
    require(!annotation.isLongRunning && !annotation.requireConfirmation) {
      "ReflectiveTools builds plain tools only; @Tool.isLongRunning and requireConfirmation on" +
        " ${method.name} are not supported here."
    }
    val name = annotation.name.ifEmpty { method.name }
    return ReflectiveTool(instance, method, name, annotation.description)
  }

  private class Bound(val index: Int, val spec: Param, val type: Class<*>, val required: Boolean)

  private class ReflectiveTool(
    private val instance: Any,
    private val method: Method,
    name: String,
    description: String,
  ) : BaseFutureTool(name, description) {

    private val contextIndex: Int =
      method.parameterTypes.indexOfFirst { ToolContext::class.java.isAssignableFrom(it) }

    private val bound: List<Bound> =
      method.parameters
        .withIndex()
        .filter { it.index != contextIndex }
        .map { (i, parameter) ->
          val spec =
            requireNotNull(parameter.getAnnotation(Param::class.java)) {
              "Parameter #$i of ${method.name} needs @Param(name = ...): Java does not retain" +
                " parameter names in bytecode, so the FunctionDeclaration cannot be derived without it."
            }
          require(spec.name.isNotBlank()) {
            "Parameter #$i of ${method.name} needs a non-blank @Param(name = ...): Java does not" +
              " retain parameter names in bytecode."
          }
          // Java carries no nullability, so AUTO and REQUIRED both mean required; only OPTIONAL
          // opts out.
          val required = spec.required != Requiredness.OPTIONAL
          require(required || !parameter.type.isPrimitive) {
            "Optional parameter '${spec.name}' of ${method.name} must be a boxed type, not a" +
              " primitive: a primitive cannot receive the null passed when the argument is omitted."
          }
          Bound(i, spec, parameter.type, required)
        }

    init {
      method.isAccessible = true
      // Wire names key both the schema and the args lookup, so they must be unique.
      val duplicates = bound.groupingBy { it.spec.name }.eachCount().filterValues { it > 1 }.keys
      require(duplicates.isEmpty()) {
        "Tool '$name' has parameters with duplicate @Param names: $duplicates."
      }
    }

    override fun declaration(): FunctionDeclaration {
      val declaration = FunctionDeclaration.builder().name(name).description(description)
      if (bound.isEmpty()) {
        return declaration.build()
      }
      val properties = mutableMapOf<String, Schema>()
      val required = mutableListOf<String>()
      for (b in bound) {
        properties[b.spec.name] = schemaFor(b.type, b.spec.description)
        if (b.required) {
          required.add(b.spec.name)
        }
      }
      var schema = Schema.builder().type(Type.OBJECT).properties(properties)
      if (required.isNotEmpty()) {
        schema = schema.required(required)
      }
      return declaration.parameters(schema.build()).build()
    }

    override fun runAsync(context: ToolContext, args: Map<String, Any?>): CompletableFuture<Any> =
      CompletableFuture.supplyAsync {
        invokeReflectively(context, args)
      }

    private fun invokeReflectively(context: ToolContext, args: Map<String, Any?>): Any {
      val actual = arrayOfNulls<Any>(method.parameterCount)
      if (contextIndex >= 0) {
        actual[contextIndex] = context
      }
      for (b in bound) {
        val raw = args[b.spec.name]
        require(raw != null || !b.required) {
          "Tool '$name' is missing required argument '${b.spec.name}'."
        }
        actual[b.index] = coerce(raw, b.type)
      }
      return try {
        method.invoke(instance, *actual) ?: emptyMap<String, Any>()
      } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        throw when (cause) {
          is RuntimeException -> cause
          is Error -> cause
          else -> RuntimeException(cause)
        }
      }
    }

    /** JSON has one number type; Java has six. Coerce rather than let [Method.invoke] throw. */
    private fun coerce(value: Any?, target: Class<*>): Any? {
      if (value == null || target.isInstance(value)) {
        return value
      }
      return when (target) {
        Int::class.javaPrimitiveType,
        Int::class.javaObjectType -> (value as Number).toInt()
        Long::class.javaPrimitiveType,
        Long::class.javaObjectType -> (value as Number).toLong()
        Double::class.javaPrimitiveType,
        Double::class.javaObjectType -> (value as Number).toDouble()
        Float::class.javaPrimitiveType,
        Float::class.javaObjectType -> (value as Number).toFloat()
        String::class.java -> value.toString()
        else ->
          if (target.isEnum) {
            val constants = target.enumConstants!!
            constants.firstOrNull { (it as Enum<*>).name == value.toString() }
              ?: throw IllegalArgumentException(
                "Tool '$name' received an unknown value for enum parameter of type" +
                  " ${target.simpleName}; expected one of ${constants.map { (it as Enum<*>).name }}."
              )
          } else {
            value
          }
      }
    }

    private fun schemaFor(type: Class<*>, description: String): Schema {
      val kind =
        when {
          type == String::class.java || type == CharSequence::class.java -> Type.STRING
          type == Int::class.javaPrimitiveType ||
            type == Int::class.javaObjectType ||
            type == Long::class.javaPrimitiveType ||
            type == Long::class.javaObjectType -> Type.INTEGER
          type == Double::class.javaPrimitiveType ||
            type == Double::class.javaObjectType ||
            type == Float::class.javaPrimitiveType ||
            type == Float::class.javaObjectType -> Type.NUMBER
          type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType ->
            Type.BOOLEAN
          type.isEnum -> Type.STRING
          // List/Map are unsupported: erased generics hide the element type, so the schema would
          // lack the `items`/`properties` Vertex needs. Use a String, number, boolean or enum.
          else ->
            throw IllegalArgumentException(
              "Tool parameter type ${type.name} has no JSON schema mapping. Use a String, number," +
                " boolean or enum."
            )
        }
      var schema = Schema.builder().type(kind)
      if (description.isNotEmpty()) {
        schema = schema.description(description)
      }
      if (type.isEnum) {
        schema = schema.enumValues(type.enumConstants!!.map { (it as Enum<*>).name })
      }
      return schema.build()
    }
  }
}
