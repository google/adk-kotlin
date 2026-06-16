/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.compiler.ksp

import com.google.adk.kt.annotations.Tool as ToolAnnotation
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates [FunctionTool] implementations for functions annotated with
 * [com.google.adk.kt.annotations.Tool].
 *
 * This generator uses KotlinPoet to build the tool source code. The generation logic covers various
 * Kotlin types including primitives, enums, data classes, Lists, and Maps, including nested
 * structures and nullability.
 *
 * Concerns about brittleness are mitigated by extensive testing in `FunctionToolProcessorTest.kt`,
 * which compiles generated code for numerous function signatures and edge cases to ensure
 * correctness and prevent regressions.
 */
class FunctionToolGenerator(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) {

  /** Generates the tool implementation for the given function declaration. */
  fun generate(function: KSFunctionDeclaration): ClassName? {
    val functionName = function.simpleName.asString()
    val className = "${functionName.replaceFirstChar { it.uppercaseChar() }}Tool"

    val returnType = function.returnType?.resolve()
    val returnTypeNameString = returnType?.declaration?.qualifiedName?.asString()
    val isStreaming = returnTypeNameString == FLOW.canonicalName
    if (isStreaming) {
      logger.error("Streaming functions are not supported yet: ${functionName}", function)
      return null
    }
    val superclass = FunctionTool::class.asClassName()
    val typeBuilder = TypeSpec.classBuilder(className).superclass(superclass)

    val toolAnnotation =
      function.annotations.firstOrNull {
        it.shortName.asString() == ToolAnnotation::class.simpleName
      }
    val isLongRunning =
      toolAnnotation?.arguments?.find { it.name?.asString() == "isLongRunning" }?.value as? Boolean
        ?: false
    val requiresConfirmation =
      toolAnnotation?.arguments?.find { it.name?.asString() == "requireConfirmation" }?.value
        as? Boolean ?: false
    val customName =
      toolAnnotation?.arguments?.find { it.name?.asString() == "name" }?.value as? String
    val toolName = if (!customName.isNullOrBlank()) customName else functionName

    val customDescription =
      toolAnnotation?.arguments?.find { it.name?.asString() == "description" }?.value as? String
    val baseDesc = if (!customDescription.isNullOrBlank()) {
      customDescription
    } else {
      extractFunctionDescription(function.docString).ifBlank { "Function ${functionName}" }
    }
    val functionDesc =
      if (isLongRunning) {
        if (baseDesc.isNotBlank()) {
          "$baseDesc\n\n${FunctionTool.LONG_RUNNING_OPERATION_NOTE}"
        } else {
          FunctionTool.LONG_RUNNING_OPERATION_NOTE
        }
      } else {
        baseDesc
      }
    // Add constructor parameters for base class
    typeBuilder.addSuperclassConstructorParameter("%S", toolName)
    typeBuilder.addSuperclassConstructorParameter("%S", functionDesc)
    typeBuilder.addSuperclassConstructorParameter("%L", isLongRunning)
    // The `customMetadata` constructor parameter is not exposed via @Tool today; pass the
    // default. `requiresConfirmation` is forwarded from @Tool(requireConfirmation = ...).
    typeBuilder.addSuperclassConstructorParameter("emptyMap()")
    typeBuilder.addSuperclassConstructorParameter("%L", requiresConfirmation)

    val instanceProperty = buildPrimaryConstructor(function, typeBuilder)

    val executeFun = buildExecuteFunction(function, instanceProperty)

    if (executeFun == null) {
      logger.error("Failed to generate execute function for function ${functionName}", function)
      return null
    }
    typeBuilder.addFunction(executeFun)

    val declarationFun = buildDeclarationFunction(function, toolName, functionDesc)
    if (declarationFun != null) {
      typeBuilder.addFunction(declarationFun)
    } else {
      logger.error("Failed to generate declaration for function ${functionName}", function)
      // If declaration generation fails, we should not generate the tool at all to avoid
      // shipping an incomplete or potentially invalid tool.
      return null
    }

    val packageName = function.packageName.asString()
    val fileSpec = FileSpec.builder(packageName, className).addType(typeBuilder.build()).build()

    val dependencies = resolveDependencies(function)
    fileSpec.writeTo(codeGenerator, dependencies)

    return ClassName(packageName, className)
  }

  private fun buildPrimaryConstructor(
    function: KSFunctionDeclaration,
    typeBuilder: TypeSpec.Builder,
  ): String? {
    val parentClass = function.parentDeclaration as? KSClassDeclaration ?: return null
    val parentTypeName = parentClass.toClassName()

    val primaryConstructor =
      FunSpec.constructorBuilder().addParameter("instance", parentTypeName).build()

    typeBuilder.primaryConstructor(primaryConstructor)
    typeBuilder.addProperty(
      PropertySpec.builder("instance", parentTypeName, KModifier.PRIVATE)
        .initializer("instance")
        .build()
    )

    return "instance"
  }

  private fun buildExecuteFunction(
    function: KSFunctionDeclaration,
    instanceProperty: String?,
  ): FunSpec? {
    val executeFun =
      FunSpec.builder("execute")
        .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
        .addParameter("context", ToolContext::class.asClassName())
        .addParameter(
          "args",
          MAP.parameterizedBy(String::class.asClassName(), Any::class.asClassName()),
        )
        .returns(Any::class.asClassName())

    val invokeArgs = buildExecuteParameters(function, executeFun) ?: return null
    buildExecuteReturn(function, executeFun, instanceProperty, invokeArgs)

    return executeFun.build()
  }

  private fun buildExecuteParameters(
    function: KSFunctionDeclaration,
    executeFun: FunSpec.Builder,
  ): List<String>? {
    val invokeArgs = mutableListOf<String>()
    for (param in function.parameters) {
      val paramName = param.name?.asString() ?: continue
      val paramType = param.type.resolve()
      val typeNameString = paramType.declaration.qualifiedName?.asString()

      if (typeNameString == TOOL_CONTEXT_QUALIFIED_NAME) {
        invokeArgs.add("context")
        continue
      }

      val isNullable = paramType.isMarkedNullable
      if (param.hasDefault && !isNullable) {
        logger.error(
          "Default arguments must be nullable. Parameter '${paramName}' in " +
            "'${function.simpleName.asString()}' is not nullable.",
          param,
        )
        return null
      }

      val typeDeclaration = paramType.declaration as? KSClassDeclaration
      val isRequired = !param.hasDefault && !isNullable

      when {
        typeNameString in PRIMITIVE_OR_STRING_QUALIFIED_NAMES -> {
          buildPrimitiveParameter(paramName, typeNameString, isRequired, executeFun)
        }
        typeDeclaration?.classKind == ClassKind.ENUM_CLASS && typeNameString != null -> {
          buildEnumParameter(paramName, typeNameString, typeDeclaration, isRequired, executeFun)
        }
        typeDeclaration?.isDataClass() == true -> {
          val targetVar = "arg_${paramName}"
          val origin = "args[%S]"
          if (
            !buildDataClassParameter(
              paramName,
              targetVar,
              paramType,
              typeDeclaration,
              isRequired,
              executeFun,
              origin,
              mutableSetOf(),
            )
          ) {
            return null
          }
        }
        typeNameString == LIST_QUALIFIED_NAME -> {
          if (
            !buildListParameter(paramName, paramType, isRequired, executeFun, param, mutableSetOf())
          ) {
            return null
          }
        }
        typeNameString == MAP_QUALIFIED_NAME -> {
          if (!buildMapParameter(paramName, paramType, isRequired, executeFun, mutableSetOf())) {
            return null
          }
        }
        else -> {
          logger.error(
            "Unsupported parameter type: ${typeNameString} for parameter " +
              "'${paramName}' in '${function.simpleName.asString()}'",
            param,
          )
          return null
        }
      }
      invokeArgs.add("arg_${paramName}")
    }
    return invokeArgs
  }

  private fun getPrimitiveCoercion(typeNameString: String?, baseExpr: String): String? {
    return when (typeNameString) {
      STRING_QUALIFIED_NAME -> "($baseExpr as? String)"
      INT_QUALIFIED_NAME -> "($baseExpr as? Number)?.toInt()"
      DOUBLE_QUALIFIED_NAME -> "($baseExpr as? Number)?.toDouble()"
      FLOAT_QUALIFIED_NAME -> "($baseExpr as? Number)?.toFloat()"
      BOOLEAN_QUALIFIED_NAME -> "($baseExpr as? Boolean)"
      else -> null
    }
  }

  private fun buildPrimitiveParameter(
    paramName: String,
    typeNameString: String?,
    isRequired: Boolean,
    executeFun: FunSpec.Builder,
  ) {
    val coerceLogic = getPrimitiveCoercion(typeNameString, "args[%S]") ?: return

    executeFun.addStatement("val arg_${paramName} = ${coerceLogic}", paramName)
    if (isRequired) {
      executeFun.beginControlFlow("if (arg_${paramName} == null)")
      executeFun.addFailure("Missing required parameter ${paramName}")
      executeFun.endControlFlow()
    }
  }

  private fun buildEnumParameter(
    paramName: String,
    typeNameString: String,
    typeDeclaration: KSClassDeclaration,
    isRequired: Boolean,
    executeFun: FunSpec.Builder,
  ) {
    executeFun.addStatement("val raw_${paramName} = args[%S] as? String", paramName)
    if (isRequired) {
      executeFun.beginControlFlow("if (raw_${paramName} == null)")
      executeFun.addFailure("Missing required parameter ${paramName}")
      executeFun.endControlFlow()
    }
    val safeCall = if (isRequired) "" else "?"
    executeFun.beginControlFlow("val arg_${paramName} = try")
    executeFun.addStatement(
      "raw_${paramName}${safeCall}.let { %T.valueOf(it) }",
      typeDeclaration.toClassName(),
    )
    executeFun.nextControlFlow("catch(e: %T)", IllegalArgumentException::class.asClassName())
    executeFun.addStatement(
      "return mapOf(%T.ERROR_KEY to %P)",
      FunctionTool::class.asClassName(),
      "Invalid value for enum ${typeNameString}: \${raw_${paramName}}",
    )
    executeFun.endControlFlow()
  }

  private fun buildDataClassParameter(
    paramName: String,
    targetVar: String,
    paramType: KSType,
    typeDeclaration: KSClassDeclaration,
    isRequired: Boolean,
    executeFun: FunSpec.Builder,
    origin: String,
    visited: MutableSet<String>,
    keyName: String = paramName,
    isListItemContext: Boolean = false,
    pathName: String = paramName,
  ): Boolean {
    val qualifiedName = typeDeclaration.qualifiedName?.asString() ?: return false
    if (visited.contains(qualifiedName)) {
      logger.error("Circular dependency detected for type ${qualifiedName}")
      return false
    }
    visited.add(qualifiedName)

    if (isListItemContext) {
      executeFun.addStatement("val raw_${paramName} = $origin as? Map<String, Any>")
    } else {
      executeFun.addStatement("val raw_${paramName} = $origin as? Map<String, Any>", keyName)
    }
    if (isRequired) {
      executeFun.beginControlFlow("if (raw_${paramName} == null)")
      executeFun.addFailure("Missing required parameter ${pathName}")
      executeFun.endControlFlow()
    }

    val constructorParams = typeDeclaration.primaryConstructor?.parameters ?: emptyList()
    val constructorArgs = mutableListOf<String>()

    for (cp in constructorParams) {
      val cpName = cp.name!!.asString()
      val cpType = cp.type.resolve()
      val cpIsNullable = cpType.isMarkedNullable

      if (cp.hasDefault && !cpIsNullable) {
        logger.error(
          "Default arguments must be nullable. Parameter '${cpName}' in " +
            "'${typeDeclaration.simpleName.asString()}' is not nullable.",
          cp,
        )
        return false
      }

      val cpTypeDeclaration = cpType.declaration as? KSClassDeclaration
      val cpTypeNameString = cpType.declaration.qualifiedName?.asString()
      val cpIsRequired = !cp.hasDefault && !cpIsNullable

      val tempVarName = "${targetVar}_${cpName}"

      when {
        cpTypeNameString in PRIMITIVE_OR_STRING_QUALIFIED_NAMES -> {
          val coerceLogic =
            getPrimitiveCoercion(cpTypeNameString, "raw_${paramName}?.get(%S)") ?: return false

          executeFun.addStatement("val ${tempVarName} = ${coerceLogic}", cpName)
          if (cpIsRequired) {
            executeFun.beginControlFlow("if (${tempVarName} == null)")
            executeFun.addFailure("Missing required parameter ${pathName}.${cpName}")
            executeFun.endControlFlow()
          }
        }
        cpTypeDeclaration?.classKind == ClassKind.ENUM_CLASS && cpTypeNameString != null -> {
          executeFun.addStatement(
            "val raw_${tempVarName} = raw_${paramName}?.get(%S) as? String",
            cpName,
          )
          if (cpIsRequired) {
            executeFun.beginControlFlow("if (raw_${tempVarName} == null)")
            executeFun.addFailure("Missing required parameter ${pathName}.${cpName}")
            executeFun.endControlFlow()
          }
          executeFun.beginControlFlow("val ${tempVarName} = try")
          executeFun.addStatement(
            "raw_${tempVarName}?.let { %T.valueOf(it) }",
            cpTypeDeclaration.toClassName(),
          )
          executeFun.nextControlFlow("catch(e: %T)", IllegalArgumentException::class.asClassName())
          executeFun.addStatement(
            "return mapOf(%T.ERROR_KEY to %P)",
            FunctionTool::class.asClassName(),
            "Invalid value for enum ${cpTypeNameString}: \${raw_${tempVarName}}",
          )
          executeFun.endControlFlow()
        }
        cpTypeDeclaration?.isDataClass() == true -> {
          if (
            !buildDataClassParameter(
              "${paramName}_${cpName}",
              tempVarName,
              cpType,
              cpTypeDeclaration,
              cpIsRequired,
              executeFun,
              "raw_${paramName}?.get(%S)",
              visited,
              cpName,
              pathName = "${pathName}.${cpName}",
            )
          ) {
            return false
          }
        }
        cpTypeNameString == LIST_QUALIFIED_NAME -> {
          if (
            !buildListParameter(
              "${paramName}_${cpName}",
              cpType,
              cpIsRequired,
              executeFun,
              cp,
              visited,
              "raw_${paramName}?.get(%S)",
              cpName,
              pathName = "${pathName}.${cpName}",
            )
          ) {
            return false
          }
        }
        cpTypeNameString == MAP_QUALIFIED_NAME -> {
          if (
            !buildMapParameter(
              "${paramName}_${cpName}",
              cpType,
              cpIsRequired,
              executeFun,
              visited,
              "raw_${paramName}?.get(%S)",
              cpName,
              pathName = "${pathName}.${cpName}",
            )
          ) {
            return false
          }
        }
        else -> {
          logger.error(
            "Unsupported parameter type: ${cpTypeNameString} for parameter '${paramName}.${cpName}'",
            cp,
          )
          return false
        }
      }
      constructorArgs.add("${cpName} = ${tempVarName}")
    }

    val safeCall = if (isRequired) "" else "?"
    executeFun.addStatement(
      "val ${targetVar} = raw_${paramName}${safeCall}.let { %T(${constructorArgs.joinToString(", ")}) }",
      typeDeclaration.toClassName(),
    )
    visited.remove(qualifiedName)
    return true
  }

  private fun buildListParameter(
    paramName: String,
    paramType: KSType,
    isRequired: Boolean,
    executeFun: FunSpec.Builder,
    param: KSValueParameter,
    visited: MutableSet<String>,
    origin: String = "args[%S]",
    keyName: String = paramName,
    pathName: String = paramName,
  ): Boolean {
    val listTypeArg = paramType.arguments.firstOrNull()?.type?.resolve()
    val listTypeArgDeclaration = listTypeArg?.declaration as? KSClassDeclaration
    val listTypeNameString = listTypeArg?.declaration?.qualifiedName?.asString()
    val isListDataClass = listTypeArgDeclaration?.isDataClass() == true

    if (
      !isListDataClass &&
        (listTypeNameString == null || listTypeNameString !in PRIMITIVE_OR_STRING_QUALIFIED_NAMES)
    ) {
      logger.error(
        "Unsupported List type argument: ${listTypeNameString} for parameter '${paramName}'",
        param,
      )
      return false
    }

    executeFun.addStatement("val raw_${paramName} = $origin as? List<*>", keyName)
    if (isRequired) {
      executeFun.beginControlFlow("if (raw_${paramName} == null)")
      executeFun.addFailure("Missing required parameter ${pathName}")
      executeFun.endControlFlow()
    }

    val safeCall = if (isRequired) "" else "?"
    if (isListDataClass) {
      val resultVar = "arg_${paramName}"
      val listBuilder = "list_${paramName}"
      val itemVar = "item_${paramName}"
      val dataClassItem = "dc_${paramName}"
      executeFun.addStatement("val ${listBuilder} = mutableListOf<%T>()", listTypeArg.toClassName())
      executeFun.beginControlFlow("for (${itemVar} in raw_${paramName} ?: emptyList<Any?>())")
      if (
        !buildDataClassParameter(
          paramName + "_li",
          dataClassItem,
          listTypeArg,
          listTypeArgDeclaration,
          false,
          executeFun,
          itemVar,
          visited,
          keyName = "",
          isListItemContext = true,
          pathName = "${pathName}[]",
        )
      ) {
        return false
      }
      executeFun.beginControlFlow("if (${dataClassItem} == null)")
      executeFun.addFailure("Missing or invalid item in list ${pathName}")
      executeFun.endControlFlow()
      executeFun.addStatement("${listBuilder}.add(${dataClassItem})")
      executeFun.endControlFlow()
      executeFun.addStatement("val ${resultVar} = ${listBuilder}")
    } else {
      val elementCoercion = getPrimitiveCoercion(listTypeNameString, "it") ?: "it"
      executeFun.addStatement(
        "val arg_${paramName} = raw_${paramName}${safeCall}.mapNotNull { ${elementCoercion} }"
      )
    }
    return true
  }

  private fun buildMapParameter(
    paramName: String,
    paramType: KSType,
    isRequired: Boolean,
    executeFun: FunSpec.Builder,
    visited: MutableSet<String>,
    origin: String = "args[%S]",
    keyName: String = paramName,
    pathName: String = paramName,
  ): Boolean {
    val valueTypeArg = paramType.arguments.getOrNull(1)?.type?.resolve()
    val valueTypeNameString = valueTypeArg?.declaration?.qualifiedName?.asString()
    val valueTypeArgDeclaration = valueTypeArg?.declaration as? KSClassDeclaration
    val isMapValueDataClass = valueTypeArgDeclaration?.isDataClass() == true

    executeFun.addStatement("val raw_${paramName} = $origin as? Map<*, *>", keyName)
    if (isRequired) {
      executeFun.beginControlFlow("if (raw_${paramName} == null)")
      executeFun.addFailure("Missing required parameter ${pathName}")
      executeFun.endControlFlow()
    }

    val safeCall = if (isRequired) "" else "?"
    if (isMapValueDataClass) {
      val resultVar = "arg_${paramName}"
      val mapBuilder = "map_${paramName}"
      val entryVar = "entry_${paramName}"
      val dataClassItem = "dc_${paramName}"
      executeFun.addStatement(
        "val ${mapBuilder} = mutableMapOf<String, %T>()",
        valueTypeArg.toClassName(),
      )
      executeFun.beginControlFlow(
        "for (${entryVar} in raw_${paramName}?.entries ?: emptySet<Map.Entry<Any?, Any?>>())"
      )
      executeFun.addStatement("val key = ${entryVar}.key as? String")
      executeFun.beginControlFlow("if (key != null)")
      if (
        !buildDataClassParameter(
          paramName + "_mv",
          dataClassItem,
          valueTypeArg,
          valueTypeArgDeclaration,
          false,
          executeFun,
          "${entryVar}.value",
          visited,
          keyName = "",
          isListItemContext = true, // Treat map value as a list item context for raw value access.
          pathName = "${pathName}[\"\${key}\"]",
        )
      ) {
        return false
      }
      executeFun.beginControlFlow("if (${dataClassItem} == null)")
      executeFun.addFailure("Missing or invalid value in map ${pathName} for key \${key}")
      executeFun.endControlFlow()
      executeFun.addStatement("${mapBuilder}[key] = ${dataClassItem}")
      executeFun.endControlFlow() // if key != null
      executeFun.endControlFlow() // for loop
      executeFun.addStatement("val ${resultVar} = ${mapBuilder}.toMap()")
    } else {
      val valueCoercion = getPrimitiveCoercion(valueTypeNameString, "it.value") ?: "it.value"

      executeFun.addStatement(
        "val arg_${paramName} = raw_${paramName}${safeCall}.entries${safeCall}.mapNotNull { " +
          "val k = it.key as? String; val v = $valueCoercion; " +
          "if (k != null && v != null) k to v else null }${safeCall}.toMap()"
      )
    }
    return true
  }

  private fun buildOutputSerialization(
    valueExpr: String,
    type: KSType,
    visited: MutableSet<String>,
  ): String? {
    val typeName = type.declaration.qualifiedName?.asString()
    val typeDeclaration = type.declaration as? KSClassDeclaration
    val qualifiedName = typeDeclaration?.qualifiedName?.asString()

    if (qualifiedName != null) {
      if (visited.contains(qualifiedName)) {
        // Cycle detection
        return null
      }
      visited.add(qualifiedName)
    }

    val result =
      when {
        typeName in PRIMITIVE_OR_STRING_QUALIFIED_NAMES -> valueExpr
        typeDeclaration?.classKind == ClassKind.ENUM_CLASS ->
          if (type.isMarkedNullable) "${valueExpr}?.name" else "${valueExpr}.name"
        typeName == LIST_QUALIFIED_NAME -> {
          val listTypeArg = type.arguments.firstOrNull()?.type?.resolve()
          if (listTypeArg != null) {
            // `Any` element: pass the list through; the wire layer handles arbitrary `Any?`
            // elements.
            if (listTypeArg.declaration.qualifiedName?.asString() == ANY_QUALIFIED_NAME) {
              valueExpr
            } else {
              val elementExpr = buildOutputSerialization("it", listTypeArg, visited)
              if (elementExpr != null) {
                if (type.isMarkedNullable) {
                  "${valueExpr}?.map { ${elementExpr} }"
                } else {
                  "${valueExpr}.map { ${elementExpr} }"
                }
              } else {
                null
              }
            }
          } else {
            valueExpr
          }
        }
        typeName == MAP_QUALIFIED_NAME -> {
          val mapValueTypeArg = type.arguments.getOrNull(1)?.type?.resolve()
          if (mapValueTypeArg != null) {
            // `Any` value: pass the map through; the wire layer handles arbitrary `Any?` values.
            if (mapValueTypeArg.declaration.qualifiedName?.asString() == ANY_QUALIFIED_NAME) {
              valueExpr
            } else {
              val valueSerialization =
                buildOutputSerialization("it.value", mapValueTypeArg, visited)
              if (valueSerialization != null) {
                if (type.isMarkedNullable) {
                  "${valueExpr}?.mapValues { ${valueSerialization} }"
                } else {
                  "${valueExpr}.mapValues { ${valueSerialization} }"
                }
              } else {
                null
              }
            }
          } else {
            valueExpr
          }
        }
        typeDeclaration?.isDataClass() == true -> {
          val params = typeDeclaration.primaryConstructor?.parameters ?: emptyList()
          val mapEntries = mutableListOf<String>()
          val innerValueExpr = if (type.isMarkedNullable) "it" else valueExpr
          for (cp in params) {
            val cpName = cp.name!!.asString()
            val cpType = cp.type.resolve()
            val propSer = buildOutputSerialization("${innerValueExpr}.${cpName}", cpType, visited)
            if (propSer == null) return null // Fail fast if any property fails
            mapEntries.add("\"${cpName}\" to ${propSer}")
          }
          val mapOfExpr = "mapOf(${mapEntries.joinToString(", ")})"
          if (type.isMarkedNullable) "${valueExpr}?.let { ${mapOfExpr} }" else mapOfExpr
        }
        else -> null
      }

    if (qualifiedName != null) visited.remove(qualifiedName)
    return result
  }

  private fun buildExecuteReturn(
    function: KSFunctionDeclaration,
    executeFun: FunSpec.Builder,
    instanceProperty: String?,
    invokeArgs: List<String>,
  ) {
    val functionName = function.simpleName.asString()
    val callStatement =
      "${instanceProperty?.let { "${it}." } ?: ""}${functionName}(${invokeArgs.joinToString(", ")})"

    val returnType = function.returnType?.resolve()
    val returnTypeNameString = returnType?.declaration?.qualifiedName?.asString()

    // Exceptions thrown from the user function propagate naturally to
    // `InvocationContext.executeSingleFunctionCall`, which routes them through the framework's
    // error-tool callback pipeline (see `runErrorBaseToolCallbacks`) so plugins can either recover
    // them with a synthetic response or let them bubble up. The KSP-generated code must not
    // intercept them itself; catching here would deny callbacks the chance to recover.
    if (returnTypeNameString == UNIT_QUALIFIED_NAME || returnTypeNameString == null) {
      executeFun.addStatement(callStatement)
      // For `Unit`-returning functions, return the `Unit` singleton directly. The framework
      // recognises `Unit` as the "no response yet" signal for long-running tools (suppressing
      // the function-response event) and coerces it to an empty payload for regular tools. See
      // `BaseTool.isLongRunning`.
      executeFun.addStatement("return Unit")
    } else {
      executeFun.addStatement("val result = $callStatement")
      val serializedResult = buildOutputSerialization("result", returnType, mutableSetOf())
      if (serializedResult == null) {
        logger.error(
          "Unsupported return type for serialization: ${returnTypeNameString} in ${functionName}",
          function,
        )
        return
      }
      executeFun.addStatement(
        "return mapOf(%T.RESULT_KEY to $serializedResult)",
        BaseTool::class.asClassName(),
      )
    }
  }

  private fun buildDeclarationFunction(
    function: KSFunctionDeclaration,
    toolName: String,
    functionDesc: String,
  ): FunSpec? {
    val funSpec =
      FunSpec.builder("declaration")
        .addModifiers(KModifier.OVERRIDE)
        .returns(FunctionDeclaration::class.asClassName().copy(nullable = true))

    funSpec.addCode("return %T(\n", FunctionDeclaration::class.asClassName())
    funSpec.addCode("  name = %S,\n", toolName)
    funSpec.addCode("  description = %S,\n", functionDesc)

    val paramTypes =
      function.parameters.filter {
        it.type.resolve().declaration.qualifiedName?.asString() != TOOL_CONTEXT_QUALIFIED_NAME
      }
    if (paramTypes.isNotEmpty()) {
      funSpec.addCode("  parameters = %T(\n", Schema::class.asClassName())
      funSpec.addCode("    type = %T.OBJECT,\n", Type::class.asClassName())
      funSpec.addCode("    properties = mapOf(\n")
      for (param in paramTypes) {
        val paramName = param.name?.asString() ?: continue
        val desc = getParamDescription(param, function.docString)
        val schemaCode =
          buildSchema(param.type.resolve(), desc, mutableSetOf(), paramName) ?: return null
        funSpec.addCode("      %S to %L,\n", paramName, schemaCode)
      }
      funSpec.addCode("    ),\n")

      paramTypes
        .filter { !it.hasDefault && !it.type.resolve().isMarkedNullable }
        .mapNotNull { it.name?.asString() }
        .takeIf { it.isNotEmpty() }
        ?.let { required ->
          funSpec.addCode("    required = listOf(${required.joinToString(", ") { "\"$it\"" }}),\n")
        }
      funSpec.addCode("  )\n")
    }
    funSpec.addCode(")\n")
    return funSpec.build()
  }

  private fun extractFunctionDescription(docString: String?): String {
    if (docString == null) return ""
    return docString
      .lines()
      .map { it.trim() }
      .takeWhile { !it.startsWith("@") }
      .joinToString("\n")
      .trim()
  }

  private fun extractParamDescription(docString: String?, paramName: String): String {
    if (docString == null) return ""
    val lines = docString.lines().map { it.trim() }
    val paramStartsIndex = lines.indexOfFirst { it.startsWith("@param ${paramName}") }
    if (paramStartsIndex == -1) return ""

    val descLines = mutableListOf<String>()
    descLines.add(lines[paramStartsIndex].substringAfter("@param ${paramName}").trim())
    for (i in (paramStartsIndex + 1) until lines.size) {
      val line = lines[i]
      if (line.startsWith("@")) break
      descLines.add(line)
    }
    return descLines.joinToString("\n").trim()
  }

  private fun getParamDescription(param: KSValueParameter, docString: String?): String {
    val paramName = param.name?.asString() ?: return ""
    val paramAnnotation = param.annotations.firstOrNull { it.shortName.asString() == "Param" }
    val paramDesc =
      paramAnnotation?.arguments?.firstOrNull { it.name?.asString() == "description" }?.value
        as? String
    if (!paramDesc.isNullOrBlank()) {
      return paramDesc
    }
    return extractParamDescription(docString, paramName)
  }

  private fun resolveDependencies(function: KSFunctionDeclaration) =
    if (function.containingFile != null) {
      Dependencies(aggregating = false, function.containingFile!!)
    } else {
      Dependencies(aggregating = false)
    }

  private fun buildSchema(
    type: KSType,
    description: String,
    visited: MutableSet<String>,
    path: String,
  ): CodeBlock? {
    val typeString = type.declaration.qualifiedName?.asString()
    val typeDeclaration = type.declaration as? KSClassDeclaration
    val qualifiedName = typeDeclaration?.qualifiedName?.asString()

    if (qualifiedName != null) {
      if (visited.contains(qualifiedName)) {
        logger.error(
          "Circular dependency detected in schema for type ${qualifiedName} at path ${path}"
        )
        return null
      }
      visited.add(qualifiedName)
    }

    val typeEnum =
      when {
        typeString == STRING_QUALIFIED_NAME -> "STRING"
        typeString == INT_QUALIFIED_NAME -> "INTEGER"
        typeString == DOUBLE_QUALIFIED_NAME || typeString == FLOAT_QUALIFIED_NAME -> "NUMBER"
        typeString == BOOLEAN_QUALIFIED_NAME -> "BOOLEAN"
        typeString == LIST_QUALIFIED_NAME -> "ARRAY"
        typeString == MAP_QUALIFIED_NAME -> "OBJECT"
        typeDeclaration?.classKind == ClassKind.ENUM_CLASS -> "STRING"
        typeDeclaration?.isDataClass() == true -> "OBJECT"
        else -> "STRING"
      }

    val resultBuilder = CodeBlock.builder()
    resultBuilder.add("%T(\n", Schema::class.asClassName())
    resultBuilder.indent()
    resultBuilder.add("type = %T.%L,\n", Type::class.asClassName(), typeEnum)
    resultBuilder.add("description = %S", description)

    if (typeDeclaration?.classKind == ClassKind.ENUM_CLASS) {
      val enumValues =
        typeDeclaration.declarations
          .filterIsInstance<KSClassDeclaration>()
          .filter { it.classKind == ClassKind.ENUM_ENTRY }
          .map { it.simpleName.asString() }
          .toList()
      if (enumValues.isNotEmpty()) {
        resultBuilder.add(",\nenum = listOf(${enumValues.joinToString(", ") { "\"$it\"" }})")
      }
    }

    if (typeEnum == "OBJECT" && typeDeclaration?.isDataClass() == true) {
      val params = typeDeclaration.primaryConstructor?.parameters ?: emptyList()
      if (params.isNotEmpty()) {
        resultBuilder.add(",\nproperties = mapOf(\n")
        resultBuilder.indent()
        for (cp in params) {
          val pName = cp.name!!.asString()
          val pType = cp.type.resolve()
          val nestedSchema = buildSchema(pType, "", visited, "${path}.${pName}") ?: return null
          resultBuilder.add("%S to %L,\n", pName, nestedSchema)
        }
        resultBuilder.unindent()
        resultBuilder.add(")")
      }

      typeDeclaration.primaryConstructor
        ?.parameters
        ?.filter { !it.hasDefault && !it.type.resolve().isMarkedNullable }
        ?.mapNotNull { it.name?.asString() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { required ->
          resultBuilder.add(",\nrequired = listOf(${required.joinToString(", ") { "\"$it\"" }})")
        }
    }

    if (typeEnum == "ARRAY") {
      val listTypeArg = type.arguments.firstOrNull()?.type?.resolve()
      if (listTypeArg == null) {
        logger.error("List type at path '${path}' is missing type arguments.", type.declaration)
        return null
      }
      val itemSchema = buildSchema(listTypeArg, "", visited, "${path}[]") ?: return null
      resultBuilder.add(",\nitems = %L", itemSchema)
    }

    resultBuilder.add("\n")
    resultBuilder.unindent()
    resultBuilder.add(")")

    if (qualifiedName != null) visited.remove(qualifiedName)
    return resultBuilder.build()
  }

  private fun KSClassDeclaration.isDataClass(): Boolean = modifiers.contains(Modifier.DATA)

  fun generateExtensions(
    classDeclaration: KSClassDeclaration?,
    file: KSFile?,
    tools: List<ClassName>,
    packageName: String,
  ) {
    val extensionName =
      classDeclaration?.toClassName()?.simpleNames?.joinToString("_")?.let {
        "${it}_GeneratedTools"
      } ?: file?.fileName?.removeSuffix(".kt")?.let { "${it}Kt_GeneratedTools" } ?: "GeneratedTools"
    val fileSpecBuilder = FileSpec.builder(packageName, extensionName)

    if (classDeclaration != null) {
      val funSpec =
        FunSpec.builder("generatedTools")
          .receiver(classDeclaration.toClassName())
          .returns(List::class.asClassName().parameterizedBy(FunctionTool::class.asClassName()))

      val codeBuilder = CodeBlock.builder()
      codeBuilder.add("return listOf(\n")
      for (tool in tools) {
        codeBuilder.add("  %T(this),\n", tool)
      }
      codeBuilder.add(")\n")
      funSpec.addCode(codeBuilder.build())
      fileSpecBuilder.addFunction(funSpec.build())
    } else if (file != null) {
      val fileName = file.fileName.removeSuffix(".kt")
      val funSpec =
        FunSpec.builder("get${fileName}GeneratedTools")
          .returns(List::class.asClassName().parameterizedBy(FunctionTool::class.asClassName()))

      val codeBuilder = CodeBlock.builder()
      codeBuilder.add("return listOf(\n")
      for (tool in tools) {
        codeBuilder.add("  %T(),\n", tool)
      }
      codeBuilder.add(")\n")
      funSpec.addCode(codeBuilder.build())
      fileSpecBuilder.addFunction(funSpec.build())
    }

    val dependencies = if (file != null) Dependencies(false, file) else Dependencies(false)
    fileSpecBuilder.build().writeTo(codeGenerator, dependencies)
  }

  private fun FunSpec.Builder.addFailure(message: String) {
    addStatement("return mapOf(%T.ERROR_KEY to %S)", FunctionTool::class.asClassName(), message)
  }

  companion object {
    private val STRING_QUALIFIED_NAME = String::class.qualifiedName
    private val INT_QUALIFIED_NAME = Int::class.qualifiedName
    private val BOOLEAN_QUALIFIED_NAME = Boolean::class.qualifiedName
    private val DOUBLE_QUALIFIED_NAME = Double::class.qualifiedName
    private val FLOAT_QUALIFIED_NAME = Float::class.qualifiedName
    private val UNIT_QUALIFIED_NAME = Unit::class.qualifiedName
    private val LIST_QUALIFIED_NAME = List::class.qualifiedName
    private val MAP_QUALIFIED_NAME = Map::class.qualifiedName
    private val ANY_QUALIFIED_NAME = Any::class.qualifiedName
    private val TOOL_CONTEXT_QUALIFIED_NAME = ToolContext::class.qualifiedName
    private val PRIMITIVE_OR_STRING_QUALIFIED_NAMES =
      setOf(
        STRING_QUALIFIED_NAME,
        INT_QUALIFIED_NAME,
        DOUBLE_QUALIFIED_NAME,
        FLOAT_QUALIFIED_NAME,
        BOOLEAN_QUALIFIED_NAME,
      )

    val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
    val FLOW_MEMBER = MemberName("kotlinx.coroutines.flow", "flow")
  }
}
