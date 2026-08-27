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

import android.app.PendingIntent
import android.content.Intent
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
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `AppFunctionData` is unavailable before API 33, and the sandbox otherwise defaults below that, so
 * the SDK is pinned here rather than left to the build system.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppFunctionDataConverterTest {

  @Test
  fun toAppFunctionData_scalarArguments_setsEachDeclaredType() {
    val metadata =
      metadata(
        param("title", string()),
        param("count", AppFunctionIntTypeMetadata(isNullable = false)),
        param("size", AppFunctionLongTypeMetadata(isNullable = false)),
        param("ratio", AppFunctionDoubleTypeMetadata(isNullable = false)),
        param("done", AppFunctionBooleanTypeMetadata(isNullable = false)),
      )

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("title" to "Groceries", "count" to 3, "size" to 9L, "ratio" to 1.5, "done" to true),
      )

    assertThat(data.getString("title")).isEqualTo("Groceries")
    assertThat(data.getInt("count")).isEqualTo(3)
    assertThat(data.getLong("size")).isEqualTo(9L)
    assertThat(data.getDouble("ratio")).isEqualTo(1.5)
    assertThat(data.getBoolean("done")).isTrue()
  }

  @Test
  fun toAppFunctionData_intSuppliedForLong_widensToDeclaredType() {
    val metadata = metadata(param("size", AppFunctionLongTypeMetadata(isNullable = false)))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("size" to 7))

    assertThat(data.getLong("size")).isEqualTo(7L)
  }

  @Test
  fun toAppFunctionData_numberSuppliedForString_rendersIt() {
    val metadata = metadata(param("title", string()))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("title" to 42))

    assertThat(data.getString("title")).isEqualTo("42")
  }

  @Test
  fun toAppFunctionData_objectSuppliedForString_rejectsByName() {
    val metadata = metadata(param("title", string()))

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("title" to mapOf("a" to "b")))
      }

    assertThat(failure).hasMessageThat().contains("title")
    assertThat(failure).hasMessageThat().contains("a string")
  }

  @Test
  fun toAppFunctionData_rejectionMessage_omitsTheValue() {
    val metadata = metadata(param("count", AppFunctionIntTypeMetadata(isNullable = false)))

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to "topsecret"))
      }

    assertThat(failure).hasMessageThat().doesNotContain("topsecret")
  }

  @Test
  fun toAppFunctionData_stringSuppliedForBoolean_rejects() {
    val metadata = metadata(param("done", AppFunctionBooleanTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("done" to "yes"))
    }
  }

  @Test
  fun toAppFunctionData_omittedArgument_leavesParameterUnset() {
    val metadata = metadata(param("title", string()), param("body", string(), required = false))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("title" to "Groceries"))

    assertThat(data.containsKey("body")).isFalse()
  }

  @Test
  fun toAppFunctionData_nullArgument_leavesParameterUnset() {
    val metadata = metadata(param("body", string(), required = false))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("body" to null))

    assertThat(data.containsKey("body")).isFalse()
  }

  @Test
  fun toAppFunctionData_undeclaredArgument_isIgnored() {
    val metadata = metadata(param("title", string()))

    // A model that invents an argument must not make the whole call fail.
    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("title" to "Groceries", "invented" to "x"),
      )

    assertThat(data.getString("title")).isEqualTo("Groceries")
  }

  @Test
  fun toAppFunctionData_nestedObject_setsItsProperties() {
    val metadata = metadata(param("note", noteType()))

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("note" to mapOf("title" to "Groceries", "pages" to 2)),
      )

    val note = checkNotNull(data.getAppFunctionData("note"))
    assertThat(note.getString("title")).isEqualTo("Groceries")
    assertThat(note.getInt("pages")).isEqualTo(2)
  }

  @Test
  fun toAppFunctionData_referencedType_resolvesBeforeSetting() {
    val metadata =
      metadata(
        param("note", AppFunctionReferenceTypeMetadata("Note", isNullable = false)),
        components = AppFunctionComponentsMetadata(mapOf("Note" to noteType())),
      )

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("note" to mapOf("title" to "Groceries")),
      )

    assertThat(data.getAppFunctionData("note")?.getString("title")).isEqualTo("Groceries")
  }

  @Test
  fun toAppFunctionData_stringArray_setsList() {
    val metadata = metadata(param("tags", arrayOf(string())))

    val data =
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("tags" to listOf("a", "b")))

    assertThat(data.getStringList("tags")).containsExactly("a", "b").inOrder()
  }

  @Test
  fun toAppFunctionData_intArray_setsArray() {
    val metadata =
      metadata(param("levels", arrayOf(AppFunctionIntTypeMetadata(isNullable = false))))

    val data =
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("levels" to listOf(1, 2, 3)))

    assertThat(data.getIntArray("levels")?.toList()).containsExactly(1, 2, 3).inOrder()
  }

  @Test
  fun toAppFunctionData_objectArray_setsDataList() {
    val metadata = metadata(param("notes", arrayOf(noteType())))

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("notes" to listOf(mapOf("title" to "one"), mapOf("title" to "two"))),
      )

    val notes = checkNotNull(data.getAppFunctionDataList("notes"))
    assertThat(notes.map { it.getString("title") }).containsExactly("one", "two").inOrder()
  }

  @Test
  fun toAppFunctionData_scalarSuppliedForArray_rejects() {
    val metadata = metadata(param("tags", arrayOf(string())))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("tags" to "a"))
    }
  }

  @Test
  fun toAppFunctionData_nullInsideArray_rejects() {
    val metadata = metadata(param("tags", arrayOf(string())))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("tags" to listOf("a", null)))
    }
  }

  @Test
  fun toAppFunctionData_wholeDoubleForInt_acceptsIt() {
    val metadata = metadata(param("count", AppFunctionIntTypeMetadata(isNullable = false)))

    // A model routinely emits 2.0 where an integer is declared; it is the same number.
    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to 2.0))

    assertThat(data.getInt("count")).isEqualTo(2)
  }

  @Test
  fun toAppFunctionData_fractionalNumberForInt_rejects() {
    val metadata = metadata(param("count", AppFunctionIntTypeMetadata(isNullable = false)))

    // Truncating to 1 would send the app a number it was never given.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to 1.9))
    }
  }

  @Test
  fun toAppFunctionData_numberBeyondIntRange_rejects() {
    val metadata = metadata(param("count", AppFunctionIntTypeMetadata(isNullable = false)))

    // Narrowing would silently wrap this to a negative number.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to 3_000_000_000L))
    }
  }

  @Test
  fun toAppFunctionData_numberBeyondIntRange_isNotWrappedIntoTheData() {
    val metadata = metadata(param("count", AppFunctionLongTypeMetadata(isNullable = false)))

    // The same magnitude is fine for a 64-bit parameter.
    val data =
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to 3_000_000_000L))

    assertThat(data.getLong("count")).isEqualTo(3_000_000_000L)
  }

  @Test
  fun toAppFunctionData_valueNestedDeeperThanTheCap_rejects() {
    // A self-referential type lets the model nest a value arbitrarily deep.
    val node =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("child" to AppFunctionReferenceTypeMetadata("Node", isNullable = true)),
        required = emptyList(),
        qualifiedName = "com.example.Node",
        isNullable = false,
      )
    val metadata =
      metadata(
        param("node", node),
        components = AppFunctionComponentsMetadata(mapOf("Node" to node)),
      )
    var value = mapOf<String, Any>("child" to mapOf<String, Any>())
    repeat(40) { value = mapOf("child" to value) }

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("node" to value))
    }
  }

  @Test
  fun toAppFunctionData_arraysOfEveryScalarType_roundTrip() {
    val metadata =
      metadata(
        param("longs", arrayOf(AppFunctionLongTypeMetadata(isNullable = false))),
        param("doubles", arrayOf(AppFunctionDoubleTypeMetadata(isNullable = false))),
        param("flags", arrayOf(AppFunctionBooleanTypeMetadata(isNullable = false))),
      )

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf(
          "longs" to listOf(1L, 2L),
          "doubles" to listOf(1.5, 2.5),
          "flags" to listOf(true, false),
        ),
      )

    assertThat(data.getLongArray("longs")?.toList()).containsExactly(1L, 2L).inOrder()
    assertThat(data.getDoubleArray("doubles")?.toList()).containsExactly(1.5, 2.5).inOrder()
    assertThat(data.getBooleanArray("flags")?.toList()).containsExactly(true, false).inOrder()
  }

  @Test
  fun toAppFunctionData_nonBooleanInsideBooleanArray_rejects() {
    val metadata =
      metadata(param("flags", arrayOf(AppFunctionBooleanTypeMetadata(isNullable = false))))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("flags" to listOf(true, "no")))
    }
  }

  @Test
  fun fromReturnValue_longArray_readsList() {
    val metadata = metadata(response = arrayOf(AppFunctionLongTypeMetadata(isNullable = false)))
    val returnValue = returnValue(metadata) { setLongArray(RETURN_KEY, longArrayOf(4L, 5L)) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(listOf(4L, 5L))
  }

  @Test
  fun fromReturnValue_unsetPropertyNamedId_readsNullRatherThanZero() {
    // AppFunctionData.containsKey reports "id" as always present, so presence cannot be asked of
    // it directly; an unset id must still read as absent rather than as 0.
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("id" to AppFunctionLongTypeMetadata(isNullable = true)),
        required = emptyList(),
        qualifiedName = "com.example.Thing",
        isNullable = false,
      )
    val metadata = metadata(response = type)
    val returnValue =
      returnValue(metadata) {
        setAppFunctionData(
          RETURN_KEY,
          AppFunctionData.Builder(type, AppFunctionComponentsMetadata()).build(),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(emptyMap<String, Any?>())
  }

  @Test
  fun fromReturnValue_propertyNamedIdThatIsSet_readsTheValue() {
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("id" to AppFunctionLongTypeMetadata(isNullable = true)),
        required = emptyList(),
        qualifiedName = "com.example.Thing",
        isNullable = false,
      )
    val metadata = metadata(response = type)
    val returnValue =
      returnValue(metadata) {
        setAppFunctionData(
          RETURN_KEY,
          AppFunctionData.Builder(type, AppFunctionComponentsMetadata()).setLong("id", 7L).build(),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(mapOf("id" to 7L))
  }

  @Test
  fun toAppFunctionData_valueOutsideEnumConstraint_rejectsWithoutQuotingIt() {
    // The library's own constraint check builds the offending value into its message; that message
    // must not escape, because it is model content.
    val metadata =
      metadata(
        param(
          "mode",
          AppFunctionStringTypeMetadata(isNullable = false, enumValues = setOf("FAST", "SLOW")),
        )
      )

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("mode" to "topsecret"))
      }

    assertThat(failure).hasMessageThat().contains("mode")
    assertThat(failure).hasMessageThat().doesNotContain("topsecret")
  }

  @Test
  fun toAppFunctionData_requiredParameterOmitted_rejectsWithoutQuotingAnything() {
    val metadata = metadata(param("title", string(), required = true))

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, emptyMap())
      }

    assertThat(failure).hasMessageThat().isNotEmpty()
  }

  @Test
  fun toAppFunctionData_numberTooLargeForFloat_rejects() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    // Narrowing 1e300 would hand the app an infinity.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 1e300))
    }
  }

  @Test
  fun toAppFunctionData_floatWithinRange_isSet() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 1.5))

    assertThat(data.getFloat("ratio")).isEqualTo(1.5f)
  }

  @Test
  fun toAppFunctionData_doubleAtTheLongBoundary_rejects() {
    val metadata = metadata(param("size", AppFunctionLongTypeMetadata(isNullable = false)))

    // 2^63 is not representable as a Long; narrowing would saturate it silently.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("size" to 9.223372036854776E18))
    }
  }

  @Test
  fun toAppFunctionData_tinyNumberForFloat_rejects() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    // Narrowing 1e-300 gives 0.0f, which is not the number the model asked for.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 1e-300))
    }
  }

  @Test
  fun toAppFunctionData_zeroForFloat_isAccepted() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 0.0))

    assertThat(data.getFloat("ratio")).isEqualTo(0f)
  }

  @Test
  fun toAppFunctionData_wholeNumberTooLargeForDouble_rejects() {
    val metadata = metadata(param("ratio", AppFunctionDoubleTypeMetadata(isNullable = false)))

    // A JSON integer arrives as Long; past 2^53 a double cannot hold every one of them.
    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 9007199254740993L))
    }
  }

  @Test
  fun toAppFunctionData_missingRequiredParameter_namesIt() {
    val metadata =
      metadata(param("title", string(), required = true), param("body", string(), required = true))

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("title" to "Groceries"))
      }

    // The model can only correct itself if it is told which parameter it left out.
    assertThat(failure).hasMessageThat().contains("body")
  }

  @Test
  fun toAppFunctionData_floatArray_roundTrips() {
    val metadata =
      metadata(param("ratios", arrayOf(AppFunctionFloatTypeMetadata(isNullable = false))))

    val data =
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratios" to listOf(1.5, 2.5)))

    assertThat(data.getFloatArray("ratios")?.toList()).containsExactly(1.5f, 2.5f).inOrder()
  }

  @Test
  fun fromReturnValue_stringValue_readsIt() {
    val metadata = metadata(response = string())
    val returnValue = returnValue(metadata) { setString(RETURN_KEY, "created") }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo("created")
  }

  @Test
  fun fromReturnValue_intValue_readsIt() {
    val metadata = metadata(response = AppFunctionIntTypeMetadata(isNullable = false))
    val returnValue = returnValue(metadata) { setInt(RETURN_KEY, 7) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo(7)
  }

  @Test
  fun fromReturnValue_unsetNumber_readsNullRatherThanZero() {
    val metadata = metadata(response = AppFunctionIntTypeMetadata(isNullable = true))
    val returnValue = returnValue(metadata) {}

    // An unset numeric getter substitutes 0, which would be indistinguishable from a real result.
    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isNull()
  }

  @Test
  fun fromReturnValue_unitReturn_readsNull() {
    val metadata = metadata(response = AppFunctionUnitTypeMetadata(isNullable = false))

    // A spec-bearing fixture is unconstructible here -- the builder refuses a response missing its
    // required return value -- and the unit branch returns before any read, so the spec is moot.
    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, AppFunctionData.EMPTY)).isNull()
  }

  @Test
  fun fromReturnValue_nullableValueTheAppLeftUnset_readsNull() {
    // Only the nullable case is constructible: the builder refuses to produce a response missing a
    // value the app declared, so the failing counterpart cannot be reached from a test.
    val metadata = metadata(response = AppFunctionStringTypeMetadata(isNullable = true))
    val returnValue = returnValue(metadata) {}

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isNull()
  }

  @Test
  fun fromReturnValue_objectValue_readsSetPropertiesOnly() {
    val metadata = metadata(response = noteType())
    val returnValue =
      returnValue(metadata) {
        setAppFunctionData(
          RETURN_KEY,
          AppFunctionData.Builder(noteType(), AppFunctionComponentsMetadata())
            .setString("title", "Groceries")
            .build(),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(mapOf("title" to "Groceries"))
  }

  @Test
  fun fromReturnValue_stringArray_readsList() {
    val metadata = metadata(response = arrayOf(string()))
    val returnValue = returnValue(metadata) { setStringList(RETURN_KEY, listOf("a", "b")) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(listOf("a", "b"))
  }

  @Test
  fun fromReturnValue_objectArray_readsListOfMaps() {
    val metadata = metadata(response = arrayOf(noteType()))
    val returnValue =
      returnValue(metadata) {
        setAppFunctionDataList(
          RETURN_KEY,
          listOf(
            AppFunctionData.Builder(noteType(), AppFunctionComponentsMetadata())
              .setString("title", "one")
              .build()
          ),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(listOf(mapOf("title" to "one")))
  }

  @Test
  fun fromReturnValue_intSetToTheProbeDefault_readsItRatherThanNull() {
    // Zero is what a getter substitutes for an absent value, so this is the case the double-probe
    // exists for: a single getter could not tell it from unset.
    val metadata = metadata(response = AppFunctionIntTypeMetadata(isNullable = false))
    val returnValue = returnValue(metadata) { setInt(RETURN_KEY, 0) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo(0)
  }

  @Test
  fun fromReturnValue_booleanSetToTheProbeDefault_readsItRatherThanNull() {
    val metadata = metadata(response = AppFunctionBooleanTypeMetadata(isNullable = false))
    val returnValue = returnValue(metadata) { setBoolean(RETURN_KEY, false) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo(false)
  }

  @Test
  fun fromReturnValue_floatSetToTheProbeDefault_readsItRatherThanNull() {
    val metadata = metadata(response = AppFunctionFloatTypeMetadata(isNullable = false))
    val returnValue = returnValue(metadata) { setFloat(RETURN_KEY, 0f) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo(0f)
  }

  @Test
  fun fromReturnValue_doubleSetToTheProbeDefault_readsItRatherThanNull() {
    val metadata = metadata(response = AppFunctionDoubleTypeMetadata(isNullable = false))
    val returnValue = returnValue(metadata) { setDouble(RETURN_KEY, 0.0) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isEqualTo(0.0)
  }

  @Test
  fun fromReturnValue_nullableNumberTheAppLeftUnset_readsNull() {
    val metadata = metadata(response = AppFunctionIntTypeMetadata(isNullable = true))
    val returnValue = returnValue(metadata) {}

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isNull()
  }

  @Test
  fun fromReturnValue_allOfValue_readsPropertiesFromEveryMember() {
    val composed =
      allOf(
        objectOf("com.example.Titled", "title" to string()),
        objectOf("com.example.Paged", "pages" to AppFunctionIntTypeMetadata(isNullable = false)),
      )
    val metadata = metadata(response = composed)
    val returnValue =
      returnValue(metadata) {
        setAppFunctionData(
          RETURN_KEY,
          AppFunctionData.Builder(mergedSpec(composed), AppFunctionComponentsMetadata())
            .setString("title", "Groceries")
            .setInt("pages", 3)
            .build(),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(mapOf("title" to "Groceries", "pages" to 3))
  }

  @Test
  fun fromReturnValue_allOfArrayItems_readsEveryMergedItem() {
    val composed =
      allOf(
        objectOf("com.example.Titled", "title" to string()),
        objectOf("com.example.Paged", "pages" to AppFunctionIntTypeMetadata(isNullable = false)),
      )
    val metadata = metadata(response = arrayOf(composed))
    val returnValue =
      returnValue(metadata) {
        setAppFunctionDataList(
          RETURN_KEY,
          listOf(
            AppFunctionData.Builder(mergedSpec(composed), AppFunctionComponentsMetadata())
              .setString("title", "one")
              .setInt("pages", 1)
              .build()
          ),
        )
      }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(listOf(mapOf("title" to "one", "pages" to 1)))
  }

  @Test
  fun fromReturnValue_pendingIntentResponse_readsNothing() {
    // A screen is not a value the model can read. The toolset does not offer such a function at
    // all; this pins what the converter alone does with one.
    val metadata = metadata(response = pendingIntentType())
    val returnValue = returnValue(metadata) { setParcelable(RETURN_KEY, pendingIntent()) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue)).isNull()
  }

  @Test
  fun toAppFunctionData_allOfOfObjects_setsEveryMergedProperty() {
    val composed =
      allOf(
        objectOf("com.example.Titled", "title" to string()),
        objectOf("com.example.Paged", "pages" to AppFunctionIntTypeMetadata(isNullable = false)),
      )
    val metadata = metadata(param("note", composed))

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("note" to mapOf("title" to "Draft", "pages" to 3)),
      )

    val note = checkNotNull(data.getAppFunctionData("note"))
    assertThat(note.getString("title")).isEqualTo("Draft")
    assertThat(note.getInt("pages", 0)).isEqualTo(3)
  }

  @Test
  fun toAppFunctionData_allOfMemberBehindAReference_setsThatMembersProperties() {
    val components = AppFunctionComponentsMetadata(mapOf("Note" to noteType()))
    val metadata = metadata(param("note", allOf(reference("Note"))), components = components)

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("note" to mapOf("title" to "Draft")),
      )

    assertThat(checkNotNull(data.getAppFunctionData("note")).getString("title")).isEqualTo("Draft")
  }

  @Test
  fun toAppFunctionData_allOfMemberBehindAReferenceChain_matchesTheSpecTheAppValidates() {
    // The library follows an all-of member exactly one hop, so a chain contributes nothing. Merging
    // the chain's own properties here produced a key set the app's own spec then rejected outright.
    val components =
      AppFunctionComponentsMetadata(mapOf("Alias" to reference("Note"), "Note" to noteType()))
    val metadata = metadata(param("note", allOf(reference("Alias"))), components = components)

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("note" to mapOf("title" to "Draft")),
      )

    assertThat(data.getAppFunctionData("note")).isNotNull()
  }

  @Test
  fun fromReturnValue_floatArray_readsBackEveryItem() {
    val metadata = metadata(response = arrayOf(AppFunctionFloatTypeMetadata(isNullable = false)))
    val returnValue =
      returnValue(metadata) { setFloatArray(RETURN_KEY, floatArrayOf(1.5f, -2.25f)) }

    assertThat(AppFunctionDataConverter.fromReturnValue(metadata, returnValue))
      .isEqualTo(listOf(1.5f, -2.25f))
  }

  @Test
  fun fromReturnValue_valueNestedPastTheDepthCap_failsInsteadOfTruncating() {
    // Truncating would hand the model a partial answer inside a successful call, which nothing
    // downstream could tell apart from the whole one.
    val types = mutableListOf(objectOf("com.example.N0", "leaf" to string()))
    repeat(40) { types.add(objectOf("com.example.N${it + 1}", "child" to types.last())) }
    val empty = AppFunctionComponentsMetadata()
    val metadata = metadata(response = types.last(), components = empty)
    var nested = AppFunctionData.Builder(types[0], empty).setString("leaf", "bottom").build()
    for (level in 1 until types.size) {
      nested =
        AppFunctionData.Builder(types[level], empty).setAppFunctionData("child", nested).build()
    }
    val returnValue = returnValue(metadata) { setAppFunctionData(RETURN_KEY, nested) }

    assertFailsWith<AppFunctionDataConverter.MalformedResponse> {
      AppFunctionDataConverter.fromReturnValue(metadata, returnValue)
    }
  }

  @Test
  fun fromReturnValue_valueOfTheWrongType_failsWithAConverterOwnedType() {
    // The library reports a read mismatch with a message of its own; nothing it says about the
    // app's value may travel, so the converter substitutes one that names no value.
    val declaredAsNumber = metadata(response = AppFunctionIntTypeMetadata(isNullable = false))
    val actuallyText = metadata(response = string())
    val mismatched =
      AppFunctionData.Builder(actuallyText.response, actuallyText.components)
        .setString(RETURN_KEY, "topsecret")
        .build()

    val failure =
      assertFailsWith<AppFunctionDataConverter.MalformedResponse> {
        AppFunctionDataConverter.fromReturnValue(declaredAsNumber, mismatched)
      }

    assertThat(failure).hasMessageThat().doesNotContain("topsecret")
  }

  @Test
  fun toAppFunctionData_requiredNullableParameterOmitted_isAccepted() {
    // The library counts a nullable required parameter as optional, so refusing it here would
    // reject a call the app would have run.
    val metadata =
      metadata(param("title", AppFunctionStringTypeMetadata(isNullable = true), required = true))

    assertThat(AppFunctionDataConverter.toAppFunctionData(metadata, emptyMap())).isNotNull()
  }

  @Test
  fun toAppFunctionData_requiredNonNullParameterOmitted_isRejected() {
    val metadata = metadata(param("title", string(), required = true))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, emptyMap())
    }
  }

  @Test
  fun toAppFunctionData_wholeNumberTooLargeForADouble_isRejected() {
    val metadata = metadata(param("ratio", AppFunctionDoubleTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to Long.MAX_VALUE))
    }
  }

  @Test
  fun toAppFunctionData_nonFiniteNumber_isRejected() {
    val metadata = metadata(param("ratio", AppFunctionDoubleTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to Double.NaN))
    }
  }

  @Test
  fun toAppFunctionData_allOfMemberThatDoesNotResolve_isRejectedBeforeTheLibrarySees() {
    // Pins the ordering: constructing the builder first makes the library throw its own message
    // ("Unable to resolve ...") and this becomes the generic rejection instead.
    val metadata =
      metadata(
        param("note", allOf(reference("Missing"))),
        components = AppFunctionComponentsMetadata(),
      )

    val failure =
      assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
        AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("note" to mapOf("a" to "b")))
      }

    assertThat(failure).hasMessageThat().contains("a mergeable object")
  }

  @Test
  fun toAppFunctionData_parameterDeclaredTwice_usesTheFirstDeclaration() {
    // The library resolves the name to its first declaration, so setting the second fails the call.
    val metadata =
      metadata(param("x", string()), param("x", AppFunctionIntTypeMetadata(isNullable = false)))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("x" to "hello"))

    assertThat(data.getString("x")).isEqualTo("hello")
  }

  @Test
  fun toAppFunctionData_wholeNumberBeyondDoublePrecision_isPreservedExactly() {
    // The integral fast path earns its place here: widening through a double first would drop the
    // low bits of a long this large, and the app would receive a neighbouring number.
    val metadata = metadata(param("count", AppFunctionLongTypeMetadata(isNullable = false)))
    val beyondDoublePrecision = 9_223_372_036_854_775_806L

    val data =
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("count" to beyondDoublePrecision))

    assertThat(data.getLong("count")).isEqualTo(beyondDoublePrecision)
  }

  @Test
  fun toAppFunctionData_ordinaryDecimalForAFloat_isAccepted() {
    // No float holds 0.1 exactly, so requiring an exact round-trip would refuse almost every
    // decimal a model sends.
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    val data = AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 0.1))

    assertThat(data.getFloat("ratio")).isEqualTo(0.1f)
  }

  @Test
  fun toAppFunctionData_decimalTooLargeForAFloat_isRejected() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 1.0e40))
    }
  }

  @Test
  fun toAppFunctionData_decimalTooSmallForAFloat_isRejected() {
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 1.0e-60))
    }
  }

  @Test
  fun toAppFunctionData_wholeNumberTooLargeForAFloat_isRejected() {
    // Past 2^24 a float skips whole integers, so the app would receive a neighbouring value.
    val metadata = metadata(param("ratio", AppFunctionFloatTypeMetadata(isNullable = false)))

    assertFailsWith<AppFunctionDataConverter.RejectedArgument> {
      AppFunctionDataConverter.toAppFunctionData(metadata, mapOf("ratio" to 16_777_217))
    }
  }

  @Test
  fun toAppFunctionData_arrayOfCompositions_setsEveryItem() {
    // The write side of an all-of array: the read side is covered, this branch was not, and it is
    // the one place a builder is constructed per item from a single hoisted merge.
    val composed = allOf(objectOf("com.example.Titled", "title" to string()))
    val metadata = metadata(param("notes", arrayOf(composed)))

    val data =
      AppFunctionDataConverter.toAppFunctionData(
        metadata,
        mapOf("notes" to listOf(mapOf("title" to "first"), mapOf("title" to "second"))),
      )

    val notes = checkNotNull(data.getAppFunctionDataList("notes"))
    assertThat(notes.map { it.getString("title") }).containsExactly("first", "second").inOrder()
  }

  private companion object {
    val RETURN_KEY: String = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE

    fun string() = AppFunctionStringTypeMetadata(isNullable = false)

    fun pendingIntentType(isNullable: Boolean = false) =
      AppFunctionParcelableTypeMetadata(
        qualifiedName = "android.app.PendingIntent",
        isNullable = isNullable,
      )

    fun pendingIntent(): PendingIntent =
      PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(),
        0,
        Intent(),
        PendingIntent.FLAG_IMMUTABLE,
      )

    /**
     * The single object an all-of describes, which is the spec the app builds its value against.
     */
    fun mergedSpec(composed: AppFunctionAllOfTypeMetadata) =
      checkNotNull(
        AppFunctionTypes.flattenAllOf(composed, AppFunctionComponentsMetadata(), intArrayOf(1024))
      )

    fun arrayOf(item: AppFunctionDataTypeMetadata) =
      AppFunctionArrayTypeMetadata(itemType = item, isNullable = false)

    fun reference(name: String) =
      AppFunctionReferenceTypeMetadata(referenceDataType = name, isNullable = false)

    fun allOf(vararg members: AppFunctionDataTypeMetadata) =
      AppFunctionAllOfTypeMetadata(
        matchAll = members.toList(),
        qualifiedName = "com.example.Merged",
        isNullable = false,
      )

    fun objectOf(
      qualifiedName: String,
      vararg properties: Pair<String, AppFunctionDataTypeMetadata>,
    ) =
      AppFunctionObjectTypeMetadata(
        properties = properties.toMap(),
        required = emptyList(),
        qualifiedName = qualifiedName,
        isNullable = false,
      )

    fun noteType() =
      AppFunctionObjectTypeMetadata(
        properties =
          mapOf(
            "title" to AppFunctionStringTypeMetadata(isNullable = false),
            "pages" to AppFunctionIntTypeMetadata(isNullable = true),
          ),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    fun param(name: String, type: AppFunctionDataTypeMetadata, required: Boolean = true) =
      AppFunctionParameterMetadata(name = name, isRequired = required, dataType = type)

    fun metadata(
      vararg params: AppFunctionParameterMetadata,
      response: AppFunctionDataTypeMetadata = AppFunctionUnitTypeMetadata(isNullable = false),
      components: AppFunctionComponentsMetadata = AppFunctionComponentsMetadata(),
    ) =
      AppFunctionMetadata(
        id = "com.example.notes.NotesFunctions#createNote",
        packageName = "com.example.notes",
        isEnabled = true,
        schema = null,
        parameters = params.toList(),
        response = AppFunctionResponseMetadata(valueType = response),
        components = components,
      )

    /** Builds the response wrapper the platform hands back, holding the app's return value. */
    fun returnValue(
      metadata: AppFunctionMetadata,
      set: AppFunctionData.Builder.() -> Unit,
    ): AppFunctionData =
      AppFunctionData.Builder(metadata.response, metadata.components).apply(set).build()
  }
}
