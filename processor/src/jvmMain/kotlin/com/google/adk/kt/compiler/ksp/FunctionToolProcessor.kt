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

import com.google.adk.kt.annotations.Tool
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.ClassName

/**
 * A KSP processor that discovers functions annotated with [com.google.adk.kt.annotations.Tool] and
 * processes them.
 */
internal class FunctionToolProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val symbols = resolver.getSymbolsWithAnnotation(Tool::class.qualifiedName!!)
    val functions = symbols.filterIsInstance<KSFunctionDeclaration>()
    val generator = FunctionToolGenerator(codeGenerator, logger)

    val (classMethods, topLevelFunctions) =
      functions.partition { it.parentDeclaration is KSClassDeclaration }

    val classGroups = classMethods.groupBy { it.parentDeclaration as KSClassDeclaration }
    val fileGroups = topLevelFunctions.groupBy { it.containingFile }

    for ((classDecl, funs) in classGroups) {
      processFunctionGroup(funs, classDecl, generator)
    }

    for ((_, funs) in fileGroups) {
      processFunctionGroup(funs, null, generator)
    }

    return emptyList()
  }

  private fun processFunctionGroup(
    funs: List<KSFunctionDeclaration>,
    classDecl: KSClassDeclaration?,
    generator: FunctionToolGenerator,
  ) {
    if (funs.isEmpty()) return
    val tools = mutableListOf<ClassName>()
    val packageName = funs.first().packageName.asString()
    val file = funs.first().containingFile

    for (function in funs) {
      val tool = generator.generate(function)
      if (tool != null) {
        tools.add(tool)
      }
    }
    generator.generateExtensions(classDecl, file, tools, packageName)
  }
}
