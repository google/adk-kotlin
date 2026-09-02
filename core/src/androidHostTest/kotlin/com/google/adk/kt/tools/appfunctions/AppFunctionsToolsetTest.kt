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

@file:OptIn(ExperimentalAppFunctionsFeature::class)

package com.google.adk.kt.tools.appfunctions

import android.app.PendingIntent
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionCancelledException
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionElementAlreadyExistsException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionFunctionNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionLimitExceededException
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.AppFunctionState
import androidx.appfunctions.AppFunctionSystemUnknownException
import androidx.appfunctions.AppFunctionUnknownException
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBytesTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The toolset marshals through `AppFunctionData`, which is unavailable before API 33, so the SDK is
 * pinned here rather than left to the build system's default.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppFunctionsToolsetTest {

  @Test
  fun getTools_enabledFunction_offersItUnderASanitizedName() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function(id = "com.example.Notes#createNote")))

      val tools = toolset(client).getTools()

      assertThat(tools).hasSize(1)
      // Neither '#' nor '.' survives into a model-facing function name.
      assertThat(tools.single().name).isEqualTo("com_example_Notes_createNote")
      assertThat(tools.single().declaration()?.name).isEqualTo("com_example_Notes_createNote")
    }

  @Test
  fun getTools_disabledFunction_isNotOffered() =
    runBlocking<Unit> {
      // An app switches a function off to tell the agent it is unavailable. The runtime state
      // comes from `states`, not from the metadata's own `isEnabled`, which is always false.
      val client =
        FakeAppFunctionClient(
          listOf(function(), function(id = "com.example.Notes#deleteNote")),
          disabled = setOf("com.example.Notes#deleteNote"),
        )

      val tools = toolset(client).getTools()

      assertThat(tools.map { it.name }).containsExactly("com_example_Notes_createNote")
    }

  @Test
  fun getTools_functionMissingFromStates_isStillOffered() =
    runBlocking<Unit> {
      // The platform omits a function the caller cannot see; that is not the same as disabled, so
      // it stays offered rather than being dropped on a silence.
      val client =
        FakeAppFunctionClient(listOf(function()), invisible = setOf("com.example.Notes#createNote"))

      assertThat(toolset(client).getTools()).hasSize(1)
    }

  @Test
  fun getTools_statesQueryFails_stillOffersTheFunctions() =
    runBlocking<Unit> {
      // A failed state query says nothing about any function, so it must not wipe out the tools.
      val client = FakeAppFunctionClient(listOf(function()), statesFail = true)

      assertThat(toolset(client).getTools()).hasSize(1)
    }

  @Test
  fun getTools_twiceInOneInvocation_queriesThePlatformOnce() =
    runBlocking<Unit> {
      // getTools runs again for every streamed chunk of the model's reply, and each discovery is a
      // platform query plus a full schema conversion.
      val client = FakeAppFunctionClient(listOf(function()))
      val toolset = toolset(client)
      val context = testToolContext(testInvocationContext(invocationId = "turn-1")).context

      val first = toolset.getTools(context)
      val second = toolset.getTools(context)

      assertThat(client.searchCalls).isEqualTo(1)
      assertThat(second.map { it.name }).isEqualTo(first.map { it.name })
    }

  @Test
  fun getTools_inALaterInvocation_queriesAgain() =
    runBlocking<Unit> {
      // A newly installed or removed app has to be picked up on the next turn.
      val client = FakeAppFunctionClient(listOf(function()))
      val toolset = toolset(client)

      val first =
        toolset.getTools(testToolContext(testInvocationContext(invocationId = "turn-1")).context)
      val second =
        toolset.getTools(testToolContext(testInvocationContext(invocationId = "turn-2")).context)

      assertThat(client.searchCalls).isEqualTo(2)
      assertThat(second.map { it.name }).isEqualTo(first.map { it.name })
    }

  @Test
  fun getTools_discoveryFails_offersNothingRatherThanFailingTheTurn() =
    runBlocking<Unit> {
      // Discovery reaches AppSearch and the platform. Throwing here would abort the whole turn and
      // take every other toolset's tools with it.
      val client = FakeAppFunctionClient(listOf(function()), searchFails = true)

      assertThat(toolset(client).getTools()).isEmpty()
    }

  @Test
  fun getTools_functionWithUnsupportedRequiredParameter_isNotOffered() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(
          listOf(
            function(
              params =
                listOf(
                  AppFunctionParameterMetadata(
                    name = "blob",
                    isRequired = true,
                    dataType = AppFunctionBytesTypeMetadata(isNullable = false),
                  )
                )
            )
          )
        )

      assertThat(toolset(client).getTools()).isEmpty()
    }

  @Test
  fun getTools_collidingNames_disambiguatesThemByPackage() =
    runBlocking<Unit> {
      // Two packages can declare the same identifier; both still need a distinct model-facing name.
      val client =
        FakeAppFunctionClient(
          listOf(
            function(id = "Notes#create", packageName = "com.a"),
            function(id = "Notes#create", packageName = "com.b"),
          )
        )

      val names = toolset(client).getTools().map { it.name }

      assertThat(names).containsExactly("com_a_Notes_create", "com_b_Notes_create").inOrder()
    }

  @Test
  fun getTools_discoveryOrderReversed_producesTheSameNames() =
    runBlocking<Unit> {
      val a = function(id = "Notes#create", packageName = "com.a")
      val b = function(id = "Notes#create", packageName = "com.b")

      // The platform gives no ordering guarantee, so a name must not depend on it -- otherwise one
      // app's tool would inherit another's name from one turn to the next.
      val forward = toolset(FakeAppFunctionClient(listOf(a, b))).getTools()
      val reversed = toolset(FakeAppFunctionClient(listOf(b, a))).getTools()

      assertThat(reversed.map { it.name }).isEqualTo(forward.map { it.name })
    }

  @Test
  fun getTools_toolFilter_selectsFromTheDiscoveredFunctions() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function(id = "Notes#create"), function(id = "Notes#delete")))

      val tools =
        toolset(client, toolFilter = ToolFilter.allowList("com_example_Notes_create")).getTools()

      assertThat(tools.map { it.name }).containsExactly("com_example_Notes_create")
    }

  @Test
  fun getTools_searchesTheRequestedPackages() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function()))

      val unused = toolset(client, packageNames = setOf("com.a", "com.b")).getTools()

      assertThat(client.lastSpec?.packageNames).containsExactly("com.a", "com.b")
    }

  @Test
  fun getTools_nullPackages_searchesEveryPackage() =
    runBlocking<Unit> {
      // The platform reads a null filter as "no package filter", and returns whatever this caller
      // is allowed to see -- its own functions, or every app's with the permission.
      val client = FakeAppFunctionClient(listOf(function()))

      val unused = toolset(client, packageNames = null).getTools()

      assertThat(client.lastSpec?.packageNames).isNull()
    }

  @Test
  fun getTools_noPackages_doesNotQueryThePlatform() =
    runBlocking<Unit> {
      // An empty set is not "every package": AppFunctionSearchSpec throws on one, so it never
      // reaches the platform.
      val client = FakeAppFunctionClient(listOf(function()))

      val tools = toolset(client, packageNames = emptySet()).getTools()

      assertThat(tools).isEmpty()
      assertThat(client.searchCalls).isEqualTo(0)
    }

  @Test
  fun getTools_platformDeclaresNothing_doesNotAskForStates() =
    runBlocking<Unit> {
      // A device with no app functions is the common case, and states of nothing can only be
      // nothing, so it must not cost a second binder call every turn.
      val client = FakeAppFunctionClient(emptyList())

      val tools = toolset(client).getTools()

      assertThat(tools).isEmpty()
      assertThat(client.searchCalls).isEqualTo(1)
      assertThat(client.statesCalls).isEqualTo(0)
    }

  @Test
  fun getTools_functionsDiscovered_asksForStatesOnce() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function()))

      val tools = toolset(client).getTools()

      assertThat(tools).hasSize(1)
      assertThat(client.statesCalls).isEqualTo(1)
    }

  @Test
  @Config(sdk = [28])
  fun getTools_belowSupportedSdk_offersNothing() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function()))

      assertThat(toolset(client).getTools()).isEmpty()
      assertThat(client.searchCalls).isEqualTo(0)
    }

  @Test
  fun getTools_unsupportedDevice_offersNothing() =
    runBlocking<Unit> {
      // `getInstance` also refuses a profile user, and 34/35 without the extension library, which
      // the SDK check alone does not catch.
      val client = FakeAppFunctionClient(listOf(function()), isSupported = false)

      assertThat(toolset(client).getTools()).isEmpty()
      assertThat(client.searchCalls).isEqualTo(0)
    }

  @Test
  fun run_successfulCall_returnsTheValueUnderTheResultKey() =
    runBlocking<Unit> {
      val metadata = function(response = AppFunctionStringTypeMetadata(isNullable = false))
      val client =
        FakeAppFunctionClient(listOf(metadata)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(metadata.response, metadata.components)
              .setString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, "note-1")
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(result).isEqualTo(mapOf(BaseTool.RESULT_KEY to "note-1"))
    }

  @Test
  fun run_targetsTheDeclaringPackageAndFunction() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function(id = "Notes#create", packageName = "com.example"))) {
          ExecuteAppFunctionResponse.Success(AppFunctionData.EMPTY)
        }

      val unused = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(client.lastRequest?.targetPackageName).isEqualTo("com.example")
      assertThat(client.lastRequest?.functionIdentifier).isEqualTo("Notes#create")
    }

  @Test
  fun run_passesArgumentsThrough() =
    runBlocking<Unit> {
      val metadata = function(params = listOf(stringParam("title")))
      val client =
        FakeAppFunctionClient(listOf(metadata)) {
          ExecuteAppFunctionResponse.Success(AppFunctionData.EMPTY)
        }

      val unused =
        toolset(client).getTools().single().run(testToolContext(), mapOf("title" to "Groceries"))

      assertThat(client.lastRequest?.functionParameters?.getString("title")).isEqualTo("Groceries")
    }

  @Test
  fun run_appReportsAnError_returnsItsMessage() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function())) {
          ExecuteAppFunctionResponse.Error(AppFunctionDeniedException("caller is not allowed"))
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(result).isEqualTo(mapOf("error" to "caller is not allowed"))
    }

  @Test
  fun run_appReportsAnErrorWithABlankMessage_returnsAStableCategory() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function())) {
          ExecuteAppFunctionResponse.Error(AppFunctionDeniedException("   "))
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(result).isEqualTo(mapOf("error" to "DENIED"))
    }

  @Test
  fun run_responseThatDoesNotMatchItsMetadata_reportsItWithoutQuotingTheValue() =
    runBlocking<Unit> {
      // An app updated between discovery and execution can answer with a shape its declared
      // response does not describe. What it returned is tool-result content and must not travel.
      val declaredAsObject =
        function(
          response =
            AppFunctionObjectTypeMetadata(
              properties = mapOf("title" to AppFunctionStringTypeMetadata(isNullable = false)),
              required = listOf("title"),
              qualifiedName = "com.example.Note",
              isNullable = false,
            )
        )
      val actuallyText = function(response = AppFunctionStringTypeMetadata(isNullable = false))
      val client =
        FakeAppFunctionClient(listOf(declaredAsObject)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(actuallyText.response, actuallyText.components)
              .setString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, "topsecret")
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      @Suppress("UNCHECKED_CAST") val error = (result as Map<String, Any?>)["error"] as? String
      assertThat(error).contains("could not be read")
      assertThat(error).doesNotContain("topsecret")
    }

  @Test
  fun run_appReturnsAValueItsMetadataDoesNotDescribe_reportsItCannotBeRead() =
    runBlocking<Unit> {
      // An app updated between discovery and execution is the one case the toolset cannot prevent.
      val declaredAsNumber = function(response = AppFunctionIntTypeMetadata(isNullable = false))
      val actuallyText = function(response = AppFunctionStringTypeMetadata(isNullable = false))
      val client =
        FakeAppFunctionClient(listOf(declaredAsNumber)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(actuallyText.response, actuallyText.components)
              .setString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, "topsecret")
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      @Suppress("UNCHECKED_CAST") val error = (result as Map<String, Any?>)["error"] as? String
      assertThat(error).contains("could not be read")
      // Nothing the app returned may travel to the model.
      assertThat(error).doesNotContain("topsecret")
    }

  @Test
  fun run_appReportsAnErrorWithNoMessage_returnsAStableCategory() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function())) {
          ExecuteAppFunctionResponse.Error(AppFunctionDeniedException(null))
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      // Minification rewrites class names, so the category is derived by type instead.
      assertThat(result).isEqualTo(mapOf("error" to "DENIED"))
    }

  @Test
  fun getTools_discoveryCancelled_propagatesRatherThanSwallowing() =
    runBlocking<Unit> {
      // The rethrow in front of the broad catch: without it a cancelled turn would look like a
      // successful discovery that happened to find nothing.
      val client = FakeAppFunctionClient(listOf(function()), searchCancels = true)

      val thrown =
        runCatching { toolset(client).getTools(testToolContext(testInvocationContext()).context) }
          .exceptionOrNull()

      assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

  @Test
  fun run_errorKindThisToolsetDoesNotName_reportsAStableUnknown() =
    runBlocking<Unit> {
      // The else arm: a subclass added by a future SDK still has to reach the model as something.
      val client =
        FakeAppFunctionClient(listOf(function())) {
          ExecuteAppFunctionResponse.Error(AppFunctionUnknownException(errorCode = 9999))
        }

      val result = toolset(client).getTools().single().run(testToolContext(), mapOf())

      assertThat(result).isEqualTo(mapOf("error" to "UNKNOWN"))
    }

  @Test
  fun run_eachErrorKind_reportsItsOwnCategory() =
    runBlocking<Unit> {
      // The category is derived by type because minification rewrites class names, so every arm
      // has to be pinned to the name the model will see.
      val expected: List<Pair<AppFunctionException, String>> =
        listOf(
          AppFunctionDeniedException(null) to "DENIED",
          AppFunctionInvalidArgumentException(null) to "INVALID_ARGUMENT",
          AppFunctionDisabledException(null) to "DISABLED",
          AppFunctionFunctionNotFoundException(null) to "FUNCTION_NOT_FOUND",
          AppFunctionPermissionRequiredException(null) to "PERMISSION_REQUIRED",
          AppFunctionCancelledException(null) to "CANCELLED",
          AppFunctionElementNotFoundException(null) to "ELEMENT_NOT_FOUND",
          AppFunctionElementAlreadyExistsException(null) to "ELEMENT_ALREADY_EXISTS",
          AppFunctionLimitExceededException(null) to "LIMIT_EXCEEDED",
          AppFunctionNotSupportedException(null) to "NOT_SUPPORTED",
          AppFunctionAppUnknownException(null) to "APP_FAILED",
          AppFunctionSystemUnknownException(null) to "SYSTEM_FAILED",
        )

      for ((error, category) in expected) {
        val client =
          FakeAppFunctionClient(listOf(function())) { ExecuteAppFunctionResponse.Error(error) }
        val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())
        assertThat(result).isEqualTo(mapOf("error" to category))
      }
    }

  @Test
  fun run_argumentDoesNotFitItsType_returnsAnErrorNamingTheParameter() =
    runBlocking<Unit> {
      val client =
        FakeAppFunctionClient(listOf(function(params = listOf(stringParam("title"))))) {
          ExecuteAppFunctionResponse.Success(AppFunctionData.EMPTY)
        }

      val result =
        toolset(client).getTools().single().run(testToolContext(), mapOf("title" to listOf(1, 2)))

      @Suppress("UNCHECKED_CAST") val error = (result as Map<String, Any?>)["error"] as String
      assertThat(error).contains("title")
      // The call must not reach the app once the arguments are known not to fit.
      assertThat(client.lastRequest).isNull()
    }

  @Test
  fun run_platformUnavailable_returnsAnError() =
    runBlocking<Unit> {
      // The device stopped supporting app functions between discovery and the call.
      val client = FakeAppFunctionClient(listOf(function())) { null }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(result).isEqualTo(mapOf("error" to "App functions are unavailable on this device"))
    }

  @Test
  fun run_platformThrows_returnsAnError() =
    runBlocking<Unit> {
      // The platform can refuse a call outright instead of answering with an Error response.
      val client =
        FakeAppFunctionClient(listOf(function())) {
          // Checked, so a narrowed guard would let it escape and end the turn.
          throw AppFunctionDeniedException("denied by binder")
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      assertThat(result).isEqualTo(mapOf("error" to "The app function could not be invoked"))
    }

  @Test
  fun uniqueToolName_overLongIdentifier_keepsTheTailWithinTheLimit() {
    val id = "com.example." + "a".repeat(80) + ".Notes#createNote"

    val name = uniqueToolName("com.example", id, emptySet())

    assertThat(name.length).isAtMost(64)
    // The method name at the end is what distinguishes it from its siblings.
    assertThat(name).endsWith("Notes_createNote")
  }

  @Test
  fun uniqueToolName_identifierStartingWithADigit_isMadeToStartLegally() {
    val name = uniqueToolName("9pkg", "9lives#run", emptySet())

    // The underscore is prefixed, not substituted: dropping the leading character would let
    // 8pkg and 9pkg collide.
    assertThat(name).isEqualTo("_9pkg_9lives_run")
  }

  @Test
  fun uniqueToolName_counterAlsoTaken_advancesToTheNextOne() {
    // Pins that the counter advances rather than repeating: a sequence that never moves off 2
    // would spin forever here instead of settling on 3.
    val taken = setOf("com_a_Notes_create", "com_a_Notes_create_2")

    val name = uniqueToolName("com.a", "Notes#create", taken)

    assertThat(name).isEqualTo("com_a_Notes_create_3")
  }

  @Test
  fun uniqueToolName_qualifiedNameTaken_appendsACounter() {
    val taken = setOf("com_a_Notes_create")

    val name = uniqueToolName("com.a", "Notes#create", taken)

    assertThat(name).isEqualTo("com_a_Notes_create_2")
  }

  @Test
  fun getTools_functionThatCannotBeConverted_doesNotReserveItsName() =
    runBlocking<Unit> {
      // The dropped function must not push the next one onto a qualified name.
      val unsupported =
        function(
          id = "Notes#create",
          packageName = "com.a",
          params =
            listOf(
              AppFunctionParameterMetadata(
                name = "blob",
                isRequired = true,
                dataType = AppFunctionBytesTypeMetadata(isNullable = false),
              )
            ),
        )
      val client =
        FakeAppFunctionClient(
          listOf(unsupported, function(id = "Notes#create", packageName = "com.b"))
        )

      assertThat(toolset(client).getTools().map { it.name }).containsExactly("com_b_Notes_create")
    }

  @Test
  fun uniqueToolName_isDecidedByPackageAndIdAlone() {
    // The same function must get the same name whatever else is installed, or one app's tool
    // inherits a name the conversation history already gave another's.
    val alone = uniqueToolName("com.b", "Notes#create", emptySet())
    val crowded = uniqueToolName("com.b", "Notes#create", setOf("com_a_Notes_create"))

    assertThat(crowded).isEqualTo(alone)
  }

  @Test
  fun getTools_functionReturningAScreen_isNotOffered() =
    runBlocking<Unit> {
      // A `PendingIntent` is for the app to open, not a value the model can read, so there is
      // nothing such a call could report back.
      val client =
        FakeAppFunctionClient(
          listOf(
            function(id = "com.example.Notes#showNote", response = pendingIntentType()),
            function(id = "com.example.Notes#createNote"),
          )
        )

      val tools = toolset(client).getTools()

      assertThat(tools.single().name).endsWith("createNote")
    }

  @Test
  fun getTools_functionReturningANullableScreen_isNotOffered() =
    runBlocking<Unit> {
      // Nullable or not, the declared return is still a screen.
      val client =
        FakeAppFunctionClient(listOf(function(response = pendingIntentType(isNullable = true))))

      assertThat(toolset(client).getTools()).isEmpty()
    }

  @Test
  fun run_functionReturningSeveralScreens_reportsItCannotOpenThem() =
    runBlocking<Unit> {
      // A list of screens has nothing in it the model can read; saying so beats the empty
      // success an ordinary read would produce.
      val screens = AppFunctionArrayTypeMetadata(itemType = pendingIntentType(), isNullable = false)
      val metadata = function(response = screens)
      val client =
        FakeAppFunctionClient(listOf(metadata)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(metadata.response, metadata.components)
              .setParcelableList(
                ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE,
                listOf(testPendingIntent()),
              )
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      @Suppress("UNCHECKED_CAST") val error = (result as Map<String, Any?>)["error"] as? String
      assertThat(error).contains("returned screens")
    }

  @Test
  fun run_functionReturningAScreenInsideAnObject_reportsItCannotOpenIt() =
    runBlocking<Unit> {
      val holder =
        AppFunctionObjectTypeMetadata(
          properties = mapOf("screen" to pendingIntentType()),
          required = listOf("screen"),
          qualifiedName = "com.example.Result",
          isNullable = false,
        )
      val metadata = function(response = holder)
      val client =
        FakeAppFunctionClient(listOf(metadata)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(metadata.response, metadata.components)
              .setAppFunctionData(
                ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE,
                AppFunctionData.Builder(holder, AppFunctionComponentsMetadata())
                  .setParcelable("screen", testPendingIntent())
                  .build(),
              )
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      @Suppress("UNCHECKED_CAST") val error = (result as Map<String, Any?>)["error"] as? String
      assertThat(error).contains("returned screens")
    }

  @Test
  fun run_objectCarryingAScreenAlongsideData_stillReturnsTheData() =
    runBlocking<Unit> {
      // The screen cannot be handed over, but the rest of the object can, and the declared schema
      // promises it -- refusing the whole call would lose data the app did return.
      val holder =
        AppFunctionObjectTypeMetadata(
          properties =
            mapOf(
              "title" to AppFunctionStringTypeMetadata(isNullable = false),
              "screen" to pendingIntentType(isNullable = true),
            ),
          required = listOf("title"),
          qualifiedName = "com.example.Result",
          isNullable = false,
        )
      val metadata = function(response = holder)
      val client =
        FakeAppFunctionClient(listOf(metadata)) {
          ExecuteAppFunctionResponse.Success(
            AppFunctionData.Builder(metadata.response, metadata.components)
              .setAppFunctionData(
                ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE,
                AppFunctionData.Builder(holder, AppFunctionComponentsMetadata())
                  .setString("title", "Groceries")
                  .build(),
              )
              .build()
          )
        }

      val result = toolset(client).getTools().single().run(testToolContext(), emptyMap())

      @Suppress("UNCHECKED_CAST") val value = (result as Map<String, Any?>)[BaseTool.RESULT_KEY]
      assertThat(value).isEqualTo(mapOf("title" to "Groceries"))
    }

  @Test
  fun getTools_noInvocationToKeyOn_rediscoversOnEveryCall() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function()))

      val toolset = toolset(client)
      val first = toolset.getTools(readonlyContext = null)
      val second = toolset.getTools(readonlyContext = null)

      assertThat(client.searchCalls).isEqualTo(2)
      assertThat(second.map { it.name }).isEqualTo(first.map { it.name })
    }

  @Test
  fun close_afterDiscovery_dropsTheCachedTools() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function()))
      val context = testToolContext(testInvocationContext(invocationId = "turn-1")).context

      val toolset = toolset(client)
      val before = toolset.getTools(context)
      toolset.close()
      val after = toolset.getTools(context)

      // The same invocation would otherwise have been answered from the cache.
      assertThat(client.searchCalls).isEqualTo(2)
      assertThat(after.map { it.name }).isEqualTo(before.map { it.name })
    }

  @Test
  fun close_duringDiscovery_doesNotLeaveTheToolsCached() =
    runBlocking<Unit> {
      // close() cannot take the discovery lock, so a discovery already inside it must not write
      // the cache back once close() has returned.
      var created: AppFunctionsToolset? = null
      val client = FakeAppFunctionClient(listOf(function()), onSearch = { created?.close() })
      val toolset = toolset(client).also { created = it }
      val context = testToolContext(testInvocationContext(invocationId = "turn-1")).context

      val before = toolset.getTools(context)
      val after = toolset.getTools(context)

      // A cache that survived the close would have answered the second call without searching.
      assertThat(client.searchCalls).isEqualTo(2)
      assertThat(after.map { it.name }).isEqualTo(before.map { it.name })
    }

  @Test
  fun getTools_nonScreenParcelableReturn_isStillOffered() =
    runBlocking<Unit> {
      // Proves the screen filter matches a PendingIntent rather than any parcelable.
      val other =
        AppFunctionParcelableTypeMetadata(qualifiedName = "com.example.Other", isNullable = false)
      val client = FakeAppFunctionClient(listOf(function(response = other)))

      assertThat(toolset(client).getTools().single().declaration()?.response).isNull()
    }

  @Test
  fun getTools_toolDescription_matchesItsDeclaration() =
    runBlocking<Unit> {
      val client = FakeAppFunctionClient(listOf(function(description = "Creates a note")))

      val tool = toolset(client).getTools().single()

      assertThat(tool.description).isEqualTo("Creates a note")
      assertThat(tool.declaration()?.description).isEqualTo("Creates a note")
    }

  /** An [AppFunctionClient] that answers from a fixed list and records what it was asked. */
  private class FakeAppFunctionClient(
    private val functions: List<AppFunctionMetadata>,
    private val searchFails: Boolean = false,
    private val searchCancels: Boolean = false,
    private val statesFail: Boolean = false,
    private val disabled: Set<String> = emptySet(),
    private val invisible: Set<String> = emptySet(),
    override val isSupported: Boolean = true,
    /** Runs inside [search], so a test can act while a discovery is in flight. */
    private val onSearch: () -> Unit = {},
    private val respond: (ExecuteAppFunctionRequest) -> ExecuteAppFunctionResponse? = {
      ExecuteAppFunctionResponse.Success(AppFunctionData.EMPTY)
    },
  ) : AppFunctionClient {
    var searchCalls = 0
    var lastSpec: AppFunctionSearchSpec? = null
    var lastRequest: ExecuteAppFunctionRequest? = null

    override suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata> {
      searchCalls++
      lastSpec = spec
      onSearch()
      // Checked, like the AppFunctionException and AppSearchException this guard exists for, so
      // the test fails if the catch is ever narrowed back to RuntimeException.
      if (searchCancels) throw CancellationException("the turn was abandoned")
      if (searchFails) throw AppFunctionDeniedException("app search is unavailable")
      return functions
    }

    var statesCalls = 0

    override suspend fun states(names: List<AppFunctionName>): List<AppFunctionState> {
      statesCalls++
      if (statesFail) throw AppFunctionDeniedException("states are unavailable")
      // The platform omits a function the caller cannot see, rather than reporting it disabled.
      return names
        .filterNot { it.functionIdentifier in invisible }
        .map { AppFunctionState(it, isEnabled = it.functionIdentifier !in disabled) }
    }

    override suspend fun execute(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse? {
      lastRequest = request
      return respond(request)
    }
  }

  private companion object {
    fun toolset(
      client: AppFunctionClient,
      packageNames: Set<String>? = setOf("com.example"),
      toolFilter: ToolFilter? = null,
    ) = AppFunctionsToolset(client, packageNames, toolFilter)

    fun pendingIntentType(isNullable: Boolean = false) =
      AppFunctionParcelableTypeMetadata(
        qualifiedName = "android.app.PendingIntent",
        isNullable = isNullable,
      )

    fun testPendingIntent(): PendingIntent =
      PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(),
        0,
        android.content.Intent("com.google.adk.kt.tools.appfunctions.TEST"),
        PendingIntent.FLAG_IMMUTABLE,
      )

    fun stringParam(name: String) =
      AppFunctionParameterMetadata(
        name = name,
        isRequired = false,
        dataType = AppFunctionStringTypeMetadata(isNullable = false),
      )

    fun function(
      id: String = "com.example.Notes#createNote",
      packageName: String = "com.example",
      params: List<AppFunctionParameterMetadata> = emptyList(),
      response: AppFunctionDataTypeMetadata = AppFunctionUnitTypeMetadata(isNullable = false),
      description: String = "Creates a note",
    ) =
      AppFunctionMetadata(
        id = id,
        packageName = packageName,
        // Never read: discovery takes runtime state from `states()`, and the SDK hardcodes this
        // to false whatever is passed.
        isEnabled = true,
        schema = null,
        parameters = params,
        response = AppFunctionResponseMetadata(valueType = response),
        components = AppFunctionComponentsMetadata(),
        description = description,
      )
  }
}
