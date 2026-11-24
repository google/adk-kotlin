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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testApiKeyConfig
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class DebugLoggingPluginTest {

  private val agent = DummyAgent("test_agent")
  private val tool = DummyTool("test_tool", "Test tool")
  private val invocationContext = testInvocationContext(agent = agent)
  private val callbackContext = invocationContext.toCallbackContext()
  private val toolContext = ToolContext(invocationContext)

  private fun tempFile(): File = File.createTempFile("adk_debug", ".yaml").apply { deleteOnExit() }

  private fun newPlugin(
    file: File,
    includeSessionState: Boolean = true,
    includeSystemInstruction: Boolean = true,
  ) =
    DebugLoggingPlugin(
      outputPath = file.absolutePath,
      includeSessionState = includeSessionState,
      includeSystemInstruction = includeSystemInstruction,
    )

  private suspend fun DebugLoggingPlugin.startInvocation() {
    assertEquals(CallbackChoice.Continue(Unit), beforeRun(invocationContext))
  }

  /** Returns the text of the debug file written for one user message holding [text]. */
  private suspend fun writtenUserMessage(text: String): String {
    val file = tempFile()
    val plugin = newPlugin(file)
    val message = Content(parts = listOf(Part(text = text)))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)
    return file.readText()
  }

  /**
   * Returns the text of the debug file written for an invocation whose session ends with [state].
   */
  private suspend fun writtenStateSnapshot(vararg state: Pair<String, Any>): String {
    val file = tempFile()
    val plugin = newPlugin(file)
    state.forEach { (key, value) -> invocationContext.session.state[key] = value }

    plugin.startInvocation()
    plugin.afterRun(invocationContext)
    return file.readText()
  }

  /** Returns the text of the debug file written for one event carrying [stateDelta]. */
  private suspend fun writtenStateDelta(vararg stateDelta: Pair<String, Any>): String {
    val file = tempFile()
    val plugin = newPlugin(file)
    val event =
      Event(author = "test_agent", actions = EventActions(stateDelta = mutableMapOf(*stateDelta)))

    plugin.startInvocation()
    assertEquals(event, plugin.onEvent(invocationContext, event))
    plugin.afterRun(invocationContext)
    return file.readText()
  }

  @Test
  fun defaultName_isDebugLoggingPlugin() {
    assertEquals("debug_logging_plugin", DebugLoggingPlugin().name)
  }

  @Test
  fun writesInvocationDocumentToFile() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "---")
    assertContains(text, "invocation_id: test-invocation-id")
    assertContains(text, "session_id: test_session_id")
    assertContains(text, "app_name: test_app_name")
    assertContains(text, "entry_type: invocation_start")
    assertContains(text, "entry_type: invocation_end")
  }

  @Test
  fun capturesUserMessageContentInFull() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val message = Content(parts = listOf(Part(text = "hello world")))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: user_message")
    assertContains(text, "hello world")
  }

  @Test
  fun onUserMessage_privateKeyWithoutEndLine_redactsItToTheEnd() = runBlocking {
    val text = writtenUserMessage("key follows: -----BEGIN PGP PRIVATE KEY BLOCK-----\n$SENTINEL")

    assertContains(text, "key follows: <redacted>")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_privateKeyMidString_keepsTheTextAroundIt() = runBlocking {
    val text = writtenUserMessage("here is my key $PRIVATE_KEY please rotate it")

    assertContains(text, "text: here is my key <redacted> please rotate it\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_rsaPrivateKeyWithEndLine_redactsOnlyTheBlock() = runBlocking {
    val text = writtenUserMessage("rsa key $RSA_PRIVATE_KEY end of note")

    assertContains(text, "text: rsa key <redacted> end of note\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_twoPrivateKeys_keepsTheTextBetweenThem() = runBlocking {
    val text = writtenUserMessage("first $PRIVATE_KEY keep this $PGP_PRIVATE_KEY last")

    assertContains(text, "text: first <redacted> keep this <redacted> last\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_proseWithHeaderFragments_writesItUnchanged() = runBlocking {
    val prose = "notes about a PRIVATE KEY----- and -----BEGIN elsewhere"

    val text = writtenUserMessage(prose)

    assertContains(text, "text: $prose\n")
    assertFalse(text.contains("<redacted>"))
  }

  @Test
  fun capturesLlmRequestAndResponse() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val request =
      LlmRequest(
        model = DummyModel("dummy-model"),
        contents = listOf(Content(parts = listOf(Part(text = "prompt text")))),
      )
    val response = LlmResponse(content = Content(parts = listOf(Part(text = "response text"))))

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(request), plugin.beforeModel(callbackContext, request))
    assertEquals(response, plugin.afterModel(callbackContext, response))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: llm_request")
    assertContains(text, "dummy-model")
    assertContains(text, "prompt text")
    assertContains(text, "entry_type: llm_response")
    assertContains(text, "response text")
  }

  @Test
  fun capturesToolCallAndResult() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val args = mapOf<String, Any>("city" to "Zurich")
    val result = mapOf<String, Any>("temperature" to "20")

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(args), plugin.beforeTool(toolContext, tool, args))
    assertEquals(result, plugin.afterTool(toolContext, tool, args, result))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: tool_call")
    assertContains(text, "test_tool")
    assertContains(text, "Zurich")
    assertContains(text, "entry_type: tool_response")
    assertContains(text, "temperature")
  }

  @Test
  fun beforeTool_keyFileInArg_writesItWithoutThePrivateKey() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val keyFile =
      """{"private_key": "-----BEGIN PRIVATE KEY-----\n$SENTINEL\n-----END PRIVATE KEY-----\n",""" +
        """ "client_email": "agent@example.com"}"""
    val args: Map<String, Any?> = mapOf("key_file" to keyFile)

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(args), plugin.beforeTool(toolContext, tool, args))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "<redacted>")
    assertContains(text, "agent@example.com")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onToolError_privateKeyInErrorMessage_writesItWithoutThePrivateKey() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val error = IllegalStateException("bad key $PRIVATE_KEY")

    plugin.startInvocation()
    assertEquals(
      CallbackChoice.Continue(Unit),
      plugin.onToolError(toolContext, tool, emptyMap(), error),
    )
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "error_message: bad key <redacted>\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun capturesSignInRequestCountWithoutTheConfig() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val config = testApiKeyConfig(rawKey = "sentinel-api-key")
    val event =
      Event(
        author = "test_agent",
        actions = EventActions(requestedAuthConfigs = mutableMapOf("call_1" to config)),
      )

    plugin.startInvocation()
    assertEquals(event, plugin.onEvent(invocationContext, event))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "requested_auth_configs: 1")
    assertFalse(text.contains("sentinel-api-key"))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterTool_typedOAuth2Credential_writesItsRedactedToString() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val result = mapOf<String, Any>("credential" to oauth2Credential())

    plugin.startInvocation()
    assertEquals(result, plugin.afterTool(toolContext, tool, emptyMap(), result))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "credential: OAuth2(variant=OAUTH2, clientId=sentinel-client,")
    assertContains(text, "authUri=<redacted>,")
    assertContains(text, "nonce=<redacted>,")
    assertContains(text, "state=<redacted>,")
    assertFalse(text.contains(SENTINEL))
    assertFalse(text.contains(SIGN_IN_STATE))
    assertFalse(text.contains(SIGN_IN_NONCE))
    assertFalse(text.contains("auth.example"))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_typedApiKeyInState_writesItsToString() = runBlocking {
    val text = writtenStateSnapshot("my_key" to AuthCredential.ApiKey(Secret(SENTINEL)))

    assertContains(text, "my_key: ApiKey(apiKey=<redacted>)")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_typedOAuth2InState_writesItsRedactedToString() = runBlocking {
    val text = writtenStateSnapshot("my_key" to oauth2Credential())

    assertContains(text, "my_key: OAuth2(variant=OAUTH2, clientId=sentinel-client,")
    assertContains(text, "authUri=<redacted>,")
    assertContains(text, "nonce=<redacted>,")
    assertContains(text, "state=<redacted>,")
    assertFalse(text.contains(SENTINEL))
    assertFalse(text.contains(SIGN_IN_STATE))
    assertFalse(text.contains(SIGN_IN_NONCE))
    assertFalse(text.contains("auth.example"))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_typedCredentialUnderACredentialKey_writesItsToString() = runBlocking {
    val text = writtenStateSnapshot(CREDENTIAL_KEY to AuthCredential.ApiKey(Secret(SENTINEL)))

    assertContains(text, "$CREDENTIAL_KEY: ApiKey(apiKey=<redacted>)")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterRun_bareTokenUnderACredentialKey_writesItRedacted() = runBlocking {
    val text = writtenStateSnapshot(CREDENTIAL_KEY to SENTINEL)

    assertContains(text, "$CREDENTIAL_KEY: <redacted>")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterRun_bareTokenUnderATempCredentialKey_writesItRedacted() = runBlocking {
    val text = writtenStateSnapshot("temp:$CREDENTIAL_KEY" to SENTINEL)

    assertContains(text, "temp:$CREDENTIAL_KEY: <redacted>")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onEvent_bareTokenUnderACredentialKeyInStateDelta_writesItRedacted() = runBlocking {
    val text = writtenStateDelta(CREDENTIAL_KEY to SENTINEL)

    assertContains(text, "$CREDENTIAL_KEY: <redacted>")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterRun_credentialInASetInState_writesItWithoutItsKey() = runBlocking {
    val text =
      writtenStateSnapshot("keys" to setOf(mapOf("authType" to "apiKey", "apiKey" to SENTINEL)))

    assertContains(text, "authType: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_signInRequestWithAPlainConfig_writesItWithoutItsSecret() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val config =
      mapOf(
        "authScheme" to mapOf("type" to "apiKey", "name" to "X-Key", "in" to "header"),
        "rawAuthCredential" to mapOf("authType" to "apiKey", "apiKey" to SENTINEL),
      )
    val request =
      FunctionCall(
        name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
        id = "req-1",
        args = mapOf("functionCallId" to "c1", "authConfig" to config),
      )
    val message = Content(parts = listOf(Part(functionCall = request)))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)

    // Typed before it is written, so it prints as a typed credential without its key.
    val text = file.readText()
    assertContains(text, "ApiKey(apiKey=<redacted>)")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterRun_plainApiKeyInState_writesItWithoutItsKey() = runBlocking {
    // As a reload hands back a credential saved under the app's own key: plain values.
    val text = writtenStateSnapshot("my_key" to mapOf("authType" to "apiKey", "apiKey" to SENTINEL))

    assertContains(text, "authType: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_plainOAuth2InState_writesItWithoutItsSecretsOrSignInData() = runBlocking {
    val plain =
      mapOf(
        "authType" to "oauth2",
        "oauth2" to
          mapOf(
            "clientId" to "sentinel-client",
            "clientSecret" to SENTINEL,
            "authUri" to "https://auth.example/authorize?state=$SIGN_IN_STATE",
            "state" to SIGN_IN_STATE,
            "nonce" to SIGN_IN_NONCE,
          ),
      )
    val text = writtenStateSnapshot("my_key" to plain)

    assertContains(text, "clientId: sentinel-client\n")
    assertFalse(text.contains(SENTINEL))
    assertFalse(text.contains(SIGN_IN_STATE))
    assertFalse(text.contains(SIGN_IN_NONCE))
    assertFalse(text.contains("auth.example"))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_nestedTypedOAuth2InState_writesItsRedactedToString() = runBlocking {
    val text = writtenStateSnapshot("my_key" to mapOf("nested" to oauth2Credential()))

    assertContains(text, "nested: OAuth2(variant=OAUTH2, clientId=sentinel-client,")
    assertContains(text, "authUri=<redacted>,")
    assertContains(text, "nonce=<redacted>,")
    assertContains(text, "state=<redacted>,")
    assertFalse(text.contains(SENTINEL))
    assertFalse(text.contains(SIGN_IN_STATE))
    assertFalse(text.contains(SIGN_IN_NONCE))
    assertFalse(text.contains("auth.example"))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun afterRun_typedAuthConfigInState_redactsItsCredentialKey() = runBlocking {
    val text = writtenStateSnapshot("my_key" to testApiKeyConfig(rawKey = SENTINEL))

    assertContains(text, "credentialKey=<redacted>)")
    assertFalse(text.contains("adk_apiKey_"))
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onEvent_plainCredentialInStateDelta_writesItWithoutItsKey() = runBlocking {
    val text = writtenStateDelta("plain" to mapOf("authType" to "apiKey", "apiKey" to SENTINEL))

    assertContains(text, "authType: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterRun_plainCredentialNestedInState_writesItWithoutItsKey() = runBlocking {
    val nested = mapOf("tool" to "x", "cred" to mapOf("authType" to "apiKey", "apiKey" to SENTINEL))

    val text = writtenStateSnapshot("cfg" to nested)

    assertContains(text, "tool: x")
    assertContains(text, "authType: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterTool_plainCredentialNestedInResult_writesItWithoutItsKey() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val result = mapOf("auth" to listOf(mapOf("authType" to "apiKey", "apiKey" to SENTINEL)))

    plugin.startInvocation()
    assertEquals(result, plugin.afterTool(toolContext, tool, emptyMap(), result))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "authType: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun onUserMessage_plainCredentialInFunctionResponse_writesItWithoutItsKey() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    // In the snake_case form a request body can carry.
    val response = mapOf("cred" to mapOf("auth_type" to "apiKey", "api_key" to SENTINEL))
    val call = FunctionResponse(name = "f", response = response, id = "c1")
    val message = Content(parts = listOf(Part(functionResponse = call)))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "auth_type: apiKey\n")
    assertFalse(text.contains(SENTINEL))
  }

  @Test
  fun afterTool_pageTokenInResult_writesItAsGiven() = runBlocking {
    // Credentials are recognized by their JSON form, not by key name, so a page token stays.
    val file = tempFile()
    val plugin = newPlugin(file)
    val result = mapOf("nextPageToken" to "page-2")

    plugin.startInvocation()
    assertEquals(result, plugin.afterTool(toolContext, tool, emptyMap(), result))
    plugin.afterRun(invocationContext)

    assertContains(file.readText(), "nextPageToken: page-2")
  }

  @Test
  fun afterTool_sortedMapWithNonStringKeysInResult_writesItAsGiven() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val result = mapOf("byYear" to sortedMapOf(2024 to 3))

    plugin.startInvocation()
    assertEquals(result, plugin.afterTool(toolContext, tool, emptyMap(), result))
    plugin.afterRun(invocationContext)

    assertContains(file.readText(), "'2024': 3")
  }

  @Test
  fun onUserMessage_bareKeyInSignInResponse_writesItRedacted() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val response =
      FunctionResponse(
        name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
        response = mapOf("result" to SENTINEL),
        id = "req-1",
      )
    val message = Content(parts = listOf(Part(functionResponse = response)))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)

    assertFalse(file.readText().contains(SENTINEL))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun onEvent_typedCredentialInStateDelta_writesItsRedactedToString() = runBlocking {
    val text = writtenStateDelta("typed" to oauth2Credential())

    assertContains(text, "typed: OAuth2(variant=OAUTH2, clientId=sentinel-client,")
    assertContains(text, "authUri=<redacted>,")
    assertContains(text, "nonce=<redacted>,")
    assertContains(text, "state=<redacted>,")
    assertFalse(text.contains(SENTINEL))
    assertFalse(text.contains(SIGN_IN_STATE))
    assertFalse(text.contains(SIGN_IN_NONCE))
    assertFalse(text.contains("auth.example"))
  }

  @Test
  fun onEvent_removedStateInStateDelta_writesTheRemovedMarker() = runBlocking {
    val text = writtenStateDelta("gone" to State.REMOVED)

    assertContains(text, "gone: __ADK_SENTINEL_REMOVED__")
  }

  @Test
  fun afterRun_contentInState_writesItsToString() = runBlocking {
    val text = writtenStateSnapshot("content" to Content(parts = listOf(Part(text = "hi"))))

    assertContains(text, "content: Content(role=null, parts=[Part(text=hi,")
  }

  @Test
  fun includeSessionStateFalse_omitsSnapshot() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file, includeSessionState = false)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    assertFalse(file.readText().contains("session_state_snapshot"))
  }

  @Test
  fun includeSystemInstructionFalse_marksPresenceWithoutLeakingText() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file, includeSystemInstruction = false)
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "secret instruction")))
          )
      )

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(request), plugin.beforeModel(callbackContext, request))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "has_system_instruction")
    assertFalse(text.contains("secret instruction"))
  }

  @Test
  fun missingBeforeRun_doesNotWriteFile() = runBlocking {
    val file = tempFile()
    file.delete()
    val plugin = newPlugin(file)

    // No beforeRun, so there is no invocation state to flush.
    plugin.afterRun(invocationContext)

    assertFalse(file.exists())
  }

  @Test
  fun restrictsOutputFileToOwner() = runBlocking {
    val file = tempFile()
    file.delete()
    val plugin = newPlugin(file)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    val posixView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
    // POSIX permissions are only asserted on filesystems that support them.
    if (posixView != null) {
      val permissions = posixView.readAttributes().permissions()
      assertContains(permissions, PosixFilePermission.OWNER_READ)
      assertContains(permissions, PosixFilePermission.OWNER_WRITE)
      assertFalse(permissions.contains(PosixFilePermission.GROUP_READ))
      assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ))
    }
  }

  private companion object {
    const val SENTINEL = "secret-sentinel"
    const val SIGN_IN_STATE = "state-sentinel"
    const val SIGN_IN_NONCE = "nonce-sentinel"
    const val CREDENTIAL_KEY = "adk_apiKey_0123456789abcdef_"
    const val PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\n$SENTINEL\n-----END PRIVATE KEY-----"
    const val RSA_PRIVATE_KEY =
      "-----BEGIN RSA PRIVATE KEY-----\n$SENTINEL\n-----END RSA PRIVATE KEY-----"
    const val PGP_PRIVATE_KEY =
      "-----BEGIN PGP PRIVATE KEY BLOCK-----\n$SENTINEL\n-----END PGP PRIVATE KEY BLOCK-----"

    /** An OAuth2 credential mid sign-in, whose secret and sign-in fields must stay out of logs. */
    @OptIn(ExperimentalAuthApi::class)
    fun oauth2Credential() =
      AuthCredential.OAuth2(
        clientId = "sentinel-client",
        clientSecret = Secret(SENTINEL),
        authUri = "https://auth.example/authorize?state=$SIGN_IN_STATE",
        nonce = SIGN_IN_NONCE,
        state = SIGN_IN_STATE,
        redirectUri = "https://auth.example/callback",
      )
  }
}
