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

package com.google.adk.kt.tools.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata

/**
 * Moves values between the model's JSON arguments and the typed [AppFunctionData] an app expects.
 *
 * An argument that does not fit its declared type is rejected by name rather than coerced, so the
 * model is told what to correct instead of the app receiving something it did not ask for.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object AppFunctionDataConverter {

  /** The key the app's return value sits under inside the response data. */
  private val RETURN_VALUE_KEY = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE

  /**
   * How deeply a value may nest while being marshalled.
   *
   * Neither side is trusted to match the schema's own depth: the arguments come from a model, and
   * the response comes from another app.
   */
  private const val MAX_VALUE_DEPTH = 32

  /** How many all-of members one composition may merge; matches the schema side's ceiling. */
  private const val MAX_ALL_OF_MEMBERS = 1024

  /** The largest whole number a double holds exactly. */
  private const val EXACT_IN_DOUBLE = 1L shl 53

  /** The largest whole number a float holds exactly. */
  private const val EXACT_IN_FLOAT = 1L shl 24

  /**
   * Builds the parameters for [metadata] from the model-supplied [args].
   *
   * An argument the model omitted is left unset; if the app declared it required, building the
   * parameters fails rather than reaching the app.
   *
   * @throws IllegalArgumentException if an argument does not fit its declared type, nests too
   *   deeply, or leaves a required parameter unset. The message names the parameter, never the
   *   value.
   */
  fun toAppFunctionData(metadata: AppFunctionMetadata, args: Map<String, Any?>): AppFunctionData {
    val builder = AppFunctionData.Builder(metadata.parameters, metadata.components)
    // The library resolves a repeated name to its first declaration; setting a later one too would
    // fail every call to a function whose metadata declares one name twice.
    val declared = metadata.parameters.distinctBy { it.name }
    for (parameter in declared) {
      val value = args[parameter.name] ?: continue
      try {
        set(builder, parameter.name, parameter.dataType, value, metadata.components, depth = 0)
      } catch (e: RejectedArgument) {
        throw e
      } catch (_: IllegalArgumentException) {
        // Dropped rather than chained: the library's own checks quote the offending value back in
        // the message, which is model or user content and must not travel.
        reject(parameter.name, "a value the app accepts")
      } catch (_: IllegalStateException) {
        reject(parameter.name, "a value the app accepts")
      }
    }
    // A nullable required parameter is optional to the library, so demanding one here would refuse
    // a call the app accepts.
    val missing = declared.firstOrNull {
      it.isRequired && !it.dataType.isNullable && args[it.name] == null
    }
    if (missing != null) reject(missing.name, "a value, because the function requires it")
    // The check above is what names the parameter, and it already refuses more than `build()` does
    // -- including a required parameter named `id`, which `containsKey` always reports present. The
    // catch is here so that a check the library adds later reaches the model as a refusal rather
    // than as a message quoting the argument back, the way every nested build already does.
    return try {
      builder.build()
    } catch (_: IllegalArgumentException) {
      rejectCall()
    } catch (_: IllegalStateException) {
      rejectCall()
    }
  }

  /**
   * Reads the app's return value out of [returnValue] as a JSON-native value.
   *
   * Returns `null` for a function that returns nothing, and for a value whose type has no
   * JSON-native reading.
   *
   * @throws MalformedResponse if the app's value does not match the type its metadata declared, or
   *   nests deeper than this can read. The message never carries the app's value.
   */
  // The library's read messages quote the offending value, and the catch cannot tell which did, so
  // the cause is dropped rather than chained onto content the model must not see.
  @Suppress("UnusedException")
  fun fromReturnValue(metadata: AppFunctionMetadata, returnValue: AppFunctionData): Any? =
    try {
      read(returnValue, RETURN_VALUE_KEY, metadata.response.valueType, metadata.components, 0)
    } catch (_: IllegalArgumentException) {
      throw MalformedResponse("The app's response did not match the type its metadata declared")
    } catch (_: IllegalStateException) {
      // The library validates on read too and quotes the offending value back; a return value is
      // tool-result content and must not travel.
      throw MalformedResponse("The app's response did not match the type its metadata declared")
    }

  /** Sets one declared parameter or property, resolving what its type actually is first. */
  private fun set(
    builder: AppFunctionData.Builder,
    key: String,
    declared: AppFunctionDataTypeMetadata,
    value: Any,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ) {
    if (depth >= MAX_VALUE_DEPTH) reject(key, "a value nested no deeper than $MAX_VALUE_DEPTH")
    when (
      val type = AppFunctionTypes.resolve(declared, components) ?: reject(key, "a known type")
    ) {
      is AppFunctionStringTypeMetadata -> builder.setString(key, value.asString(key))
      is AppFunctionIntTypeMetadata -> builder.setInt(key, value.asInt(key))
      is AppFunctionLongTypeMetadata -> builder.setLong(key, value.asLong(key))
      is AppFunctionFloatTypeMetadata -> builder.setFloat(key, value.asFloat(key))
      is AppFunctionDoubleTypeMetadata -> builder.setDouble(key, value.asDouble(key))
      is AppFunctionBooleanTypeMetadata ->
        builder.setBoolean(key, value as? Boolean ?: reject(key, "a boolean"))
      is AppFunctionObjectTypeMetadata ->
        builder.setAppFunctionData(key, buildObject(type, value.asMap(key), components, depth))
      is AppFunctionAllOfTypeMetadata ->
        builder.setAppFunctionData(key, buildAllOf(type, key, value.asMap(key), components, depth))
      is AppFunctionArrayTypeMetadata -> setArray(builder, key, type, value, components, depth)
      else -> reject(key, "a type this toolset can supply")
    }
  }

  /** Sets an array parameter, choosing the setter the item type calls for. */
  private fun setArray(
    builder: AppFunctionData.Builder,
    key: String,
    array: AppFunctionArrayTypeMetadata,
    value: Any,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ) {
    val items = value as? List<*> ?: reject(key, "an array")
    when (
      val itemType =
        AppFunctionTypes.resolve(array.itemType, components) ?: reject(key, "a known item type")
    ) {
      is AppFunctionStringTypeMetadata ->
        builder.setStringList(key, items.map { it.present(key).asString(key) })
      is AppFunctionIntTypeMetadata ->
        builder.setIntArray(key, IntArray(items.size) { items[it].present(key).asInt(key) })
      is AppFunctionLongTypeMetadata ->
        builder.setLongArray(key, LongArray(items.size) { items[it].present(key).asLong(key) })
      is AppFunctionFloatTypeMetadata ->
        builder.setFloatArray(key, FloatArray(items.size) { items[it].present(key).asFloat(key) })
      is AppFunctionDoubleTypeMetadata ->
        builder.setDoubleArray(
          key,
          DoubleArray(items.size) { items[it].present(key).asDouble(key) },
        )
      is AppFunctionBooleanTypeMetadata ->
        builder.setBooleanArray(
          key,
          BooleanArray(items.size) {
            items[it].present(key) as? Boolean ?: reject(key, "an array of booleans")
          },
        )
      is AppFunctionObjectTypeMetadata ->
        builder.setAppFunctionDataList(
          key,
          items.map { buildObject(itemType, it.present(key).asMap(key), components, depth) },
        )
      is AppFunctionAllOfTypeMetadata -> {
        // Merged once: the item type does not vary, and the model chooses the list's length.
        val properties = mergeOrReject(itemType, key, components).properties
        builder.setAppFunctionDataList(
          key,
          items.map {
            fill(
              AppFunctionData.Builder(itemType, components),
              properties,
              it.present(key).asMap(key),
              components,
              depth,
            )
          },
        )
      }
      else -> reject(key, "an array this toolset can supply")
    }
  }

  /** Builds a nested object from the model's map, setting only properties the object declares. */
  private fun buildObject(
    type: AppFunctionObjectTypeMetadata,
    value: Map<*, *>,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): AppFunctionData =
    fill(AppFunctionData.Builder(type, components), type.properties, value, components, depth)

  /**
   * Builds a nested all-of object, whose spec the library derives from the composition itself.
   *
   * The builder is constructed from the all-of rather than from the merged object, so the spec it
   * carries is produced by the same code the parent validates it against -- a spec merged here
   * would have to agree key for key with the library's, which follows a reference exactly one hop.
   */
  private fun buildAllOf(
    type: AppFunctionAllOfTypeMetadata,
    key: String,
    value: Map<*, *>,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): AppFunctionData {
    // Evaluated before the builder, whose constructor merges eagerly and throws on a member the
    // library cannot resolve.
    val properties = mergeOrReject(type, key, components).properties
    return fill(AppFunctionData.Builder(type, components), properties, value, components, depth)
  }

  /** The object a composition describes, rejecting the argument when it does not merge into one. */
  private fun mergeOrReject(
    type: AppFunctionAllOfTypeMetadata,
    key: String,
    components: AppFunctionComponentsMetadata,
  ): AppFunctionObjectTypeMetadata =
    // Rejecting here also keeps the builder from throwing on a reference the library cannot
    // resolve, since both apply the same one-hop rule.
    AppFunctionTypes.flattenAllOf(type, components, intArrayOf(MAX_ALL_OF_MEMBERS))
      ?: reject(key, "a mergeable object")

  /** Sets each declared property the model supplied, leaving the rest unset. */
  private fun fill(
    builder: AppFunctionData.Builder,
    properties: Map<String, AppFunctionDataTypeMetadata>,
    value: Map<*, *>,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): AppFunctionData {
    for ((name, property) in properties) {
      val propertyValue = value[name] ?: continue
      set(builder, name, property, propertyValue, components, depth + 1)
    }
    return builder.build()
  }

  /**
   * Reads one declared property as a JSON-native value.
   *
   * An optional property the app left unset reads as `null`; a required one it left unset fails the
   * read instead, because the library validates a required getter and throws before returning. A
   * value nested past [MAX_VALUE_DEPTH] also fails the read rather than being dropped, since a
   * partial answer would reach the model as a successful one.
   */
  private fun read(
    data: AppFunctionData,
    key: String,
    declared: AppFunctionDataTypeMetadata,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): Any? {
    if (depth >= MAX_VALUE_DEPTH) {
      throw MalformedResponse("The app's response nests deeper than $MAX_VALUE_DEPTH levels")
    }
    val type =
      AppFunctionTypes.resolve(declared, components)
        ?: throw MalformedResponse("The app's response declares a type that does not resolve")
    return when (type) {
      is AppFunctionStringTypeMetadata -> data.getString(key)
      is AppFunctionIntTypeMetadata -> agreed(data.getInt(key, 0), data.getInt(key, 1))
      is AppFunctionLongTypeMetadata -> agreed(data.getLong(key, 0L), data.getLong(key, 1L))
      is AppFunctionFloatTypeMetadata -> agreed(data.getFloat(key, 0f), data.getFloat(key, 1f))
      is AppFunctionDoubleTypeMetadata -> agreed(data.getDouble(key, 0.0), data.getDouble(key, 1.0))
      is AppFunctionBooleanTypeMetadata ->
        agreed(data.getBoolean(key, false), data.getBoolean(key, true))
      is AppFunctionObjectTypeMetadata ->
        data.getAppFunctionData(key)?.let { readObject(it, type, components, depth) }
      is AppFunctionAllOfTypeMetadata -> {
        // Merged first, so our caps refuse a hostile composition before the library's uncapped
        // recursion runs on it.
        val merged = merge(type, components)
        data.getAppFunctionData(key)?.let { readObject(it, merged, components, depth) }
      }
      is AppFunctionArrayTypeMetadata -> readArray(data, key, type, components, depth)
      // A unit return, bytes, a Parcelable and anything added later have no JSON-native reading.
      else -> null
    }
  }

  /** Reads an array property, choosing the getter the item type calls for. */
  private fun readArray(
    data: AppFunctionData,
    key: String,
    array: AppFunctionArrayTypeMetadata,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): Any? {
    val itemType =
      AppFunctionTypes.resolve(array.itemType, components)
        ?: throw MalformedResponse("The app's response declares an item type that does not resolve")
    return when (itemType) {
      is AppFunctionStringTypeMetadata -> data.getStringList(key)
      is AppFunctionIntTypeMetadata -> data.getIntArray(key)?.toList()
      is AppFunctionLongTypeMetadata -> data.getLongArray(key)?.toList()
      is AppFunctionFloatTypeMetadata -> data.getFloatArray(key)?.toList()
      is AppFunctionDoubleTypeMetadata -> data.getDoubleArray(key)?.toList()
      is AppFunctionBooleanTypeMetadata -> data.getBooleanArray(key)?.toList()
      is AppFunctionObjectTypeMetadata ->
        data.getAppFunctionDataList(key)?.map { readObject(it, itemType, components, depth) }
      is AppFunctionAllOfTypeMetadata -> {
        // Merged once: the item type does not vary, and the responding app chooses the length.
        val merged = merge(itemType, components)
        data.getAppFunctionDataList(key)?.map { readObject(it, merged, components, depth) }
      }
      else -> null
    }
  }

  /** Reads every declared property of an object, dropping the ones the app left unset. */
  private fun readObject(
    data: AppFunctionData,
    type: AppFunctionObjectTypeMetadata,
    components: AppFunctionComponentsMetadata,
    depth: Int,
  ): Map<String, Any?> = buildMap {
    for ((name, property) in type.properties) {
      read(data, name, property, components, depth + 1)?.let { put(name, it) }
    }
  }

  /**
   * The value both probes agree on, or `null` when they disagree because none was set.
   *
   * A numeric getter substitutes a default for an absent value, so a single read cannot tell a
   * property the app left unset from one it set to that default. [AppFunctionData.containsKey]
   * would normally answer that, but it reports the reserved `id` present whether or not it was set;
   * probing twice with different defaults is one path that holds for every key.
   */
  private fun <T> agreed(withOneDefault: T, withAnother: T): T? =
    if (withOneDefault == withAnother) withOneDefault else null

  private fun Any.asString(key: String): String =
    when (this) {
      is String -> this
      // A model routinely sends a bare number or boolean where a string is declared; rendering it
      // is what the app would have received had the model quoted it.
      is Number,
      is Boolean -> toString()
      else -> reject(key, "a string")
    }

  private fun Any.asNumber(key: String): Number = this as? Number ?: reject(key, "a number")

  /**
   * The whole number this value denotes, rejecting anything that would not survive the conversion.
   *
   * A model routinely emits `2.0` where an integer is declared, which is the same number; `2.5` and
   * a value past the type's range are not, and truncating them would send the app something it
   * never asked for.
   */
  private fun Any.asWholeNumber(key: String, expected: String): Long {
    val number = asNumber(key)
    if (number is Int || number is Long || number is Short || number is Byte) return number.toLong()
    val widened = number.toDouble()
    if (
      widened % 1.0 != 0.0 ||
        widened < Long.MIN_VALUE.toDouble() ||
        widened >= Long.MAX_VALUE.toDouble()
    ) {
      reject(key, expected)
    }
    return widened.toLong()
  }

  private fun Any.asInt(key: String): Int {
    val whole = asWholeNumber(key, "a 32-bit whole number")
    if (whole < Int.MIN_VALUE || whole > Int.MAX_VALUE) reject(key, "a 32-bit whole number")
    return whole.toInt()
  }

  private fun Any.asLong(key: String): Long = asWholeNumber(key, "a 64-bit whole number")

  /**
   * The value as a 32-bit real number, rejecting one too large to be held.
   *
   * A magnitude no float holds narrows to an infinity, and a whole number past 2^24 lands on a
   * neighbour; both are refused, while an ordinary decimal is simply narrowed.
   */
  private fun Any.asFloat(key: String): Float {
    val number = asNumber(key)
    val widened = number.toDouble()
    if (!widened.isFinite()) reject(key, "a finite number")
    val narrowed = widened.toFloat()
    // A real number only has to survive the narrowing: requiring it to round-trip exactly would
    // refuse `0.1`, which no float holds.
    if (!narrowed.isFinite() || (narrowed == 0f && widened != 0.0)) {
      reject(key, "a 32-bit real number")
    }
    // A whole one has to survive it exactly, since past 2^24 a float skips integers. Same rule as
    // asDouble at 2^53, and applied the same way -- by magnitude, before widening.
    val whole = if (number is Double || number is Float) null else number.toLong()
    if (whole != null && (whole > EXACT_IN_FLOAT || whole < -EXACT_IN_FLOAT)) {
      reject(key, "a 32-bit real number")
    }
    return narrowed
  }

  /**
   * The value as a 64-bit real number, rejecting a whole number too large to be held exactly.
   *
   * JSON integers arrive as [Long], and past 2^53 a double cannot represent every one of them, so
   * the app would receive a neighbouring value rather than the one the model asked for.
   */
  private fun Any.asDouble(key: String): Double {
    val number = asNumber(key)
    val widened = number.toDouble()
    if (!widened.isFinite()) reject(key, "a finite number")
    // Applied to every integral type, since the argument map's number type is whatever the JSON
    // layer produced, and by magnitude because `Double.toLong` saturates onto `Long.MAX_VALUE`.
    // Compared before widening: past 2^53 the widening itself is what loses the distinction.
    val whole = if (number is Double || number is Float) null else number.toLong()
    if (whole != null && (whole > EXACT_IN_DOUBLE || whole < -EXACT_IN_DOUBLE)) {
      reject(key, "a 64-bit real number")
    }
    return widened
  }

  private fun Any.asMap(key: String): Map<*, *> = this as? Map<*, *> ?: reject(key, "an object")

  private fun Any?.present(key: String): Any = this ?: reject(key, "an array without null items")

  /**
   * Reports an argument that does not fit its declared type.
   *
   * The message names the parameter and what was expected but never the value, which is model or
   * user content.
   */
  private fun reject(key: String, expected: String): Nothing =
    throw RejectedArgument("Parameter '$key' expects $expected")

  /**
   * Reports arguments the library refused as a whole, when nothing here can say which one.
   *
   * Reached only if the library rejects a set of arguments the checks above accepted, so it names
   * no parameter -- the library's message does, but that message quotes the value with it.
   */
  private fun rejectCall(): Nothing =
    throw RejectedArgument("The arguments do not fit what the function requires")

  /** The single object an all-of describes, or a failure that does not quote the app's value. */
  private fun merge(
    type: AppFunctionAllOfTypeMetadata,
    components: AppFunctionComponentsMetadata,
  ): AppFunctionObjectTypeMetadata =
    AppFunctionTypes.flattenAllOf(type, components, intArrayOf(MAX_ALL_OF_MEMBERS))
      ?: throw MalformedResponse("The app's response declares a composition that does not merge")

  /**
   * A response that does not match what the app's own metadata declared.
   *
   * Distinguishable from the library's own faults, and never carrying the value it describes.
   */
  class MalformedResponse(message: String) : RuntimeException(message)

  /**
   * An argument that does not fit, described without quoting it.
   *
   * Distinguishable from the library's own rejections, whose messages embed the value.
   */
  class RejectedArgument(message: String) : IllegalArgumentException(message)
}
