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

package com.google.adk.kt

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import java.io.File
import java.io.FileNotFoundException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/**
 * Reads and writes the YAML files ADK exchanges with people.
 *
 * A deployment describes its services in one and a recording is written into one, so on both sides
 * there is a person opening the file and reading it. That is what the writing half is arranged
 * around: a multiline string stays multiline, and a field the author never set is left out.
 */
object YamlUtils {

  /**
   * Loads the YAML document in [filePath] as ordinary Kotlin values.
   *
   * A mapping comes back as a [Map], a sequence as a [List], and scalars as [String], [Boolean] or
   * a boxed number; an empty document loads as `null`. The parse is a safe one, so a tag naming a
   * type to build is refused rather than obeyed.
   *
   * @throws FileNotFoundException if [filePath] is not an existing file. The path is named in the
   *   message, because a caller that logs the failure has only the message to go on.
   */
  fun loadYamlFile(filePath: String): Any? {
    val file = File(filePath)
    if (!file.isFile) {
      throw FileNotFoundException("YAML file not found: $filePath")
    }
    return file.reader(Charsets.UTF_8).use { safeYaml().load(it) }
  }

  /**
   * Writes [value] to [filePath] as a YAML document a person can read.
   *
   * [value] is encoded through its `kotlinx.serialization` serializer, so a field left at its
   * default and a field that is null are both omitted at any depth, while a field set away from its
   * default is kept even when the value is false. Keys are written in alphabetical order at every
   * depth, and any missing parent directory is created.
   */
  inline fun <reified T> dumpToYaml(value: T, filePath: String) {
    dumpToYaml(serializer<T>(), value, filePath)
  }

  /**
   * The body of [dumpToYaml], with the serializer already resolved.
   *
   * Not part of the API: it exists only because the public function is inline, which is how it
   * reaches a serializer for a type its caller names.
   */
  @PublishedApi
  @OptIn(FrameworkInternalApi::class)
  internal fun <T> dumpToYaml(serializer: SerializationStrategy<T>, value: T, filePath: String) {
    // adkJson is the SDK's persistence encoder, and its two exclusions are exactly the ones wanted
    // here: defaults are not encoded and nulls are not encoded.
    val document = toPlainValue(adkJson.encodeToJsonElement(serializer, value))
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writer(Charsets.UTF_8).use { readableYaml().dump(document, it) }
  }

  /**
   * The JSON tree as the maps, lists and scalars snakeyaml knows how to write, keys sorted at every
   * depth.
   *
   * Not the SDK's `jsonElementToAny`, which reads a lone `__ADK_SENTINEL_REMOVED__` key back as the
   * `State.REMOVED` object. That object has no properties, so it writes as a class-tagged empty
   * mapping that [loadYamlFile] then refuses to read.
   */
  private fun toPlainValue(element: JsonElement): Any? =
    when (element) {
      is JsonNull -> null
      is JsonPrimitive ->
        when {
          element.isString -> element.content
          element.booleanOrNull != null -> element.boolean
          element.longOrNull != null -> element.long
          element.doubleOrNull != null -> element.double
          else -> element.content
        }
      is JsonObject -> element.mapValues { (_, value) -> toPlainValue(value) }.toSortedMap()
      is JsonArray -> element.map { toPlainValue(it) }
    }

  /**
   * A loader that parses documents and builds nothing else.
   *
   * A [Yaml] holds parse state and is not safe to share between threads, so callers get their own.
   * The code point limit is lifted because a recorded session is a file this SDK wrote itself; what
   * makes the load safe is the [SafeConstructor], which still builds nothing a tag asks for.
   */
  private fun safeYaml(): Yaml =
    Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = Int.MAX_VALUE }))

  /** A dumper configured for a reader rather than for a parser. See [BlockScalarRepresenter]. */
  private fun readableYaml(): Yaml {
    val options =
      DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        // Indent a sequence under the key that owns it, rather than flush against it.
        indicatorIndent = 2
        indentWithIndicator = true
        isAllowUnicode = true
        // Effectively no wrapping: a line broken at some column is a line the author did not write.
        width = 1_000_000
      }
    return Yaml(SafeConstructor(LoaderOptions()), BlockScalarRepresenter(options), options)
  }

  /**
   * Writes a string that a quoted scalar would mangle as a block scalar instead.
   *
   * The default rendering of an agent instruction escapes its newlines into one long quoted line,
   * which is correct YAML and unreadable; a string carrying a quote character gets the same
   * treatment for the same reason. snakeyaml declines the style where it would produce an invalid
   * document, a mapping key for one, and falls back to a quoted scalar there.
   */
  private class BlockScalarRepresenter(options: DumperOptions) : Representer(options) {
    override fun representScalar(tag: Tag, value: String, style: DumperOptions.ScalarStyle?): Node {
      val chosen =
        if (tag == Tag.STR && value.any { it == '\n' || it == '"' || it == '\'' }) {
          DumperOptions.ScalarStyle.LITERAL
        } else {
          style
        }
      return super.representScalar(tag, value, chosen)
    }
  }
}
