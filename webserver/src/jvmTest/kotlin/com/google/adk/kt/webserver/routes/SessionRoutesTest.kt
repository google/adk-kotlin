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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import com.google.adk.kt.webserver.jsonBody
import com.google.adk.kt.webserver.textBody
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val DEEP_NESTING = 5000

@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class SessionRoutesTest {

  open class FakeSessionService : SessionService {
    val createdSessions = mutableListOf<Session>()
    val deletedSessions = mutableSetOf<String>()

    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
      val session =
        Session(
          key = SessionKey(key.appName, key.userId, key.id ?: "gen-id"),
          state = state?.let { State(it.toMutableMap()) } ?: State(),
          events = mutableListOf(),
        )
      createdSessions.add(session)
      return session
    }

    override suspend fun getSession(
      key: SessionKey,
      config: com.google.adk.kt.sessions.GetSessionConfig?,
    ): Session? {
      return createdSessions.find { it.key.id == key.id }
    }

    override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
      return ListSessionsResponse(
        createdSessions.filter { it.key.appName == appName && it.key.userId == userId }
      )
    }

    override suspend fun deleteSession(key: SessionKey) {
      val id = checkNotNull(key.id) { "deleteSession requires a non-null id" }
      deletedSessions.add(id)
      createdSessions.removeIf { it.key.id == id }
    }

    override suspend fun listEvents(
      key: SessionKey
    ): com.google.adk.kt.sessions.ListEventsResponse = TODO()
  }

  /** A service whose [createSession] always fails with [failure]. */
  class FailingSessionService(private val failure: SessionException) : FakeSessionService() {
    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
      throw failure
  }

  /** A service that rejects an id it cannot address, as the Vertex-backed one does. */
  class IdRejectingSessionService : FakeSessionService() {
    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
      throw IllegalArgumentException("Invalid session id.")
  }

  /** A service that accepts [acceptCount] events and then fails, to exercise a partial seed. */
  class FailingAppendSessionService(private val acceptCount: Int) : FakeSessionService() {
    var appended = 0
      private set

    override suspend fun appendEvent(session: Session, event: Event): Event {
      if (appended >= acceptCount) throw IllegalStateException("append failed")
      appended++
      return super.appendEvent(session, event)
    }
  }

  @Test
  fun listSessions_empty_returnsEmptyList() = testApplication {
    val fakeService = FakeSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("[]")
  }

  @Test
  fun createSession_noBody_createsSession() = testApplication {
    // A bodyless POST stays legal, as it is on the reference server; reading the body with
    // `receive` rather than `receiveText` would turn this into a 415.
    val fakeService = FakeSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.post("/apps/testApp/users/testUser/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions).hasSize(1)
    val createdSession = fakeService.createdSessions.first()
    assertThat(createdSession.key.appName).isEqualTo("testApp")
    assertThat(createdSession.key.userId).isEqualTo("testUser")
  }

  @Test
  fun createSession_emptyJsonObject_stillCreatesSession() = testApplication {
    // What a client sends when it has no state to seed.
    val fakeService = FakeSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.post("/apps/testApp/users/testUser/sessions") { jsonBody("{}") }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions).hasSize(1)
  }

  @Test
  fun createSession_withState_seedsStateIntoStorage() = testApplication {
    // Reads the state back through GET: the route responds with the very object the service
    // returned, so asserting on the create response alone would not prove it was stored.
    application { sessionApp(InMemorySessionService()) }

    val created =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"sessionId":"s1","state":{"topic":"weather"}}""")
      }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1")

    assertThat(created.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fetched.bodyAsText()).contains("\"topic\":\"weather\"")
  }

  @Test
  fun createSession_withPrefixedState_routesEachKeyToItsScope() = testApplication {
    // `app:` and `user:` are global and `temp:` is dropped, as on the reference server. A second
    // session of the same app and user is what proves the first two were not kept session-local.
    application { sessionApp(InMemorySessionService()) }

    client.post("/apps/testApp/users/testUser/sessions") {
      jsonBody(
        """{"sessionId":"s1","state":{"app:theme":"dark","user:tier":"gold","temp:x":1,"own":"a"}}"""
      )
    }
    val second =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"s2"}""") }
    val first = client.get("/apps/testApp/users/testUser/sessions/s1")

    assertThat(second.bodyAsText()).contains("\"app:theme\":\"dark\"")
    assertThat(second.bodyAsText()).contains("\"user:tier\":\"gold\"")
    assertThat(second.bodyAsText()).doesNotContain("own")
    assertThat(first.bodyAsText()).contains("\"own\":\"a\"")
    assertThat(first.bodyAsText()).doesNotContain("temp:x")
  }

  @Test
  fun createSession_snakeCaseSessionId_isRead() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"session_id":"from-snake-case"}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions.single().key.id).isEqualTo("from-snake-case")
  }

  @Test
  fun createSession_blankSessionId_generatesOne() = testApplication {
    // Blank means "generate one" on the reference server; without the trim it is a 500.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"   "}""") }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions.single().key.id).isEqualTo("gen-id")
  }

  @Test
  fun createSession_paddedSessionId_isTrimmed() = testApplication {
    // An untrimmed id would create a session no path-param route could ever address.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"  s1  "}""") }

    assertThat(fakeService.createdSessions.single().key.id).isEqualTo("s1")
  }

  @Test
  fun createSession_duplicateSessionIdInBody_returnsConflict() = testApplication {
    application { sessionApp(InMemorySessionService()) }

    val first =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"dup"}""") }
    val second =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"dup"}""") }

    assertThat(first.status).isEqualTo(HttpStatusCode.OK)
    assertThat(second.status).isEqualTo(HttpStatusCode.Conflict)
  }

  @Test
  fun createSession_withEvents_seedsThemInOrder() = testApplication {
    application { sessionApp(InMemorySessionService()) }

    client.post("/apps/testApp/users/testUser/sessions") {
      jsonBody(
        """
        {"sessionId":"s1","events":[
          {"id":"e1","author":"user","content":{"role":"user","parts":[{"text":"first"}]}},
          {"id":"e2","author":"agent","content":{"role":"model","parts":[{"text":"second"}]}}
        ]}
        """
          .trimIndent()
      )
    }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    assertThat(fetched).contains("\"id\":\"e1\"")
    assertThat(fetched).contains("\"id\":\"e2\"")
    assertThat(fetched.indexOf("\"e1\"")).isLessThan(fetched.indexOf("\"e2\""))
  }

  @Test
  fun createSession_snakeCaseEvent_isRead() = testApplication {
    // The payoff for the alias sweep: a seed event spelled snake_case decodes into the same event.
    application { sessionApp(InMemorySessionService()) }

    client.post("/apps/testApp/users/testUser/sessions") {
      jsonBody(
        """{"session_id":"s1","events":[{"id":"e1","author":"user",""" +
          """"invocation_id":"inv-1","turn_complete":true}]}"""
      )
    }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    assertThat(fetched).contains("\"invocationId\":\"inv-1\"")
    assertThat(fetched).contains("\"turnComplete\":true")
  }

  @Test
  fun createSession_seededSession_emitsCamelCaseWithoutNulls() = testApplication {
    // The response only carries `state` and `events` once a body can seed them, so the emission
    // rule is worth asserting on a filled payload rather than on the empty session it used to be.
    application { sessionApp(InMemorySessionService()) }

    val body =
      client
        .post("/apps/testApp/users/testUser/sessions") {
          jsonBody(
            """{"sessionId":"s1","state":{"topic":"weather"},""" +
              """"events":[{"id":"e1","author":"user","invocationId":"inv-1"}]}"""
          )
        }
        .bodyAsText()

    assertThat(body).contains("\"appName\":\"testApp\"")
    assertThat(body).contains("\"userId\":\"testUser\"")
    assertThat(body).contains("\"lastUpdateTime\"")
    assertThat(body).contains("\"invocationId\":\"inv-1\"")
    assertThat(body).doesNotContain("app_name")
    assertThat(body).doesNotContain("user_id")
    assertThat(body).doesNotContain("last_update_time")
    assertThat(body).doesNotContain("invocation_id")
    assertThat(body).doesNotContain(":null")
  }

  @Test
  fun createSession_emptySession_stillEmitsTheRequiredFields() = testApplication {
    // The contract marks `state` and `events` required on a Session, so an empty one still has to
    // carry them as `{}` and `[]` rather than have them dropped for being empty.
    application { sessionApp(InMemorySessionService()) }

    val body = client.post("/apps/testApp/users/testUser/sessions").bodyAsText()

    assertThat(body).contains("\"id\":")
    assertThat(body).contains("\"appName\":\"testApp\"")
    assertThat(body).contains("\"userId\":\"testUser\"")
    assertThat(body).contains("\"state\":{}")
    assertThat(body).contains("\"events\":[]")
  }

  @Test
  fun createSession_nullStateValue_isDropped() = testApplication {
    // Session state cannot hold a null, so the key goes rather than the request failing.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"sessionId":"s1","state":{"kept":"yes","dropped":null}}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("\"kept\":\"yes\"")
    assertThat(response.bodyAsText()).doesNotContain("dropped")
  }

  @Test
  fun createSession_eventFailingItsOwnInvariant_returnsUnprocessableEntity() = testApplication {
    // CacheMetadata's `init` throws IllegalArgumentException, which is not a
    // SerializationException;
    // the broad catch still maps it to 422 (parsed but did not fit) rather than letting it escape
    // as
    // a 500.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user",""" +
            """"cache_metadata":{"fingerprint":"f","contents_count":-1}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
  }

  @Test
  fun createSession_malformedBody_returnsBadRequest() = testApplication {
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId": """) }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }

  @Test
  fun createSession_unrecognizedKey_isIgnored() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"sessionId":"s1","aFieldFromANewerClient":{"x":1}}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions.single().key.id).isEqualTo("s1")
  }

  @Test
  fun createSession_eventWithActions_isRejectedAndCreatesNothing() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user","actions":{"transferToAgent":"other"}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("event 0")
    // Validation runs before creation, so a rejected request leaves nothing behind.
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventWithSnakeCaseActions_isRejected() = testApplication {
    // Unrecognized keys are dropped on decode, so judging `actions` by the decoded value alone
    // would let this through as a 200 with the field silently discarded.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user","actions":{"transfer_to_agent":"other"}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("event 0")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventWithEmptyActions_isAccepted() = testApplication {
    // `"actions": {}` is the default and must stay legal, or a round-tripped event fails to seed.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"sessionId":"s1","events":[{"id":"e1","author":"user","actions":{}}]}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun createSession_eventWithReferenceDefaultActions_isAccepted() = testApplication {
    // Python and Go write empty stateDelta/artifactDelta (and more) on every event, so an ordinary
    // exported event carries a non-empty `actions` whose values are all empty. It must still seed.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"sessionId":"s1","events":[{"id":"e1","author":"user","actions":""" +
            """{"stateDelta":{},"artifactDelta":{},"requestedAuthConfigs":{},""" +
            """"requestedToolConfirmations":{}}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun createSession_nullBody_createsSession() = testApplication {
    // A literal `null` body means "no body", as it does on the reference server; it was a 400 here.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response = client.post("/apps/testApp/users/testUser/sessions") { jsonBody("null") }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions).hasSize(1)
  }

  @Test
  fun createSession_pythonExportedEventShape_isSeeded() = testApplication {
    // The headline case: a Python/genai-exported event carries a fractional-second timestamp,
    // snake_case keys, and empty default `actions` maps. It must decode and seed, with the seconds
    // timestamp normalized to milliseconds.
    application { sessionApp(InMemorySessionService()) }

    val created =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """
          {"session_id":"s1","events":[
            {"id":"e1","author":"user","invocation_id":"inv-1",
             "content":{"role":"user","parts":[{"text":"hi"}]},
             "actions":{"stateDelta":{},"artifactDelta":{},"requestedAuthConfigs":{},
                        "requestedToolConfirmations":{}},
             "timestamp":1730874845.9344919}
          ]}
          """
            .trimIndent()
        )
      }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    assertThat(created.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fetched).contains("\"id\":\"e1\"")
    assertThat(fetched).contains("\"invocationId\":\"inv-1\"")
    // 1730874845.9344919 s -> 1730874845934 ms.
    assertThat(fetched).contains("\"timestamp\":1730874845934")
  }

  @Test
  fun createSession_seedEventWithoutInvocationId_getsMintedId() = testApplication {
    // A client-seeded event may omit invocation_id; the route stamps a non-empty `s-` id at the
    // boundary (some backends reject an empty invocation_id on the wire), so the stored event
    // carries one whatever the backend.
    application { sessionApp(InMemorySessionService()) }

    val created =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"session_id":"s1","events":[{"id":"e1","author":"user"}]}""")
      }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    assertThat(created.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fetched).contains("\"id\":\"e1\"")
    assertThat(fetched).contains("\"invocationId\":\"s-")
  }

  @Test
  fun createSession_multipleSeedsWithoutInvocationId_sharesOneMintedId() = testApplication {
    // The id-less seeds arrive as one request, so they share a single minted `s-` id rather than
    // getting a distinct one each.
    application { sessionApp(InMemorySessionService()) }

    client.post("/apps/testApp/users/testUser/sessions") {
      jsonBody(
        """{"session_id":"s1","events":[{"id":"e1","author":"user"},""" +
          """{"id":"e2","author":"user"}]}"""
      )
    }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    val minted =
      Regex("\"invocationId\":\"(s-[^\"]+)\"").findAll(fetched).map { it.groupValues[1] }.toList()
    assertThat(minted).hasSize(2)
    assertThat(minted.toSet()).hasSize(1)
  }

  @Test
  fun createSession_mixedSeedInvocationIds_isRejected() = testApplication {
    // Some events carry an invocationId and some omit it; minting for only the gaps would mix real
    // and synthetic invocations, so the request is rejected before any session is created.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user","invocation_id":"inv-1"},""" +
            """{"id":"e2","author":"user"}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_objectNestedDeepEnoughToExhaustTheStack_isRejected() = testApplication {
    // Deep objects exhaust the stack while decoding the free-form state map; the resulting Error is
    // not a shape mismatch and would otherwise escape as a 500.
    application { sessionApp(InMemorySessionService()) }
    val deep = buildString {
      repeat(DEEP_NESTING) { append("""{"a":""") }
      append("1")
      repeat(DEEP_NESTING) { append("}") }
    }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"state":{"k":$deep}}""") }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }

  @Test
  fun createSession_arrayNestedDeepEnoughToExhaustTheStack_isRejected() = testApplication {
    // Arrays are parsed by stack recursion (objects are not), so a deep array exhausts the stack in
    // parseToJsonElement itself; the Error must still answer 400, not escape as a 500.
    application { sessionApp(InMemorySessionService()) }
    val deep = "[".repeat(DEEP_NESTING) + "]".repeat(DEEP_NESTING)

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"state":{"k":$deep}}""") }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }

  @Test
  fun createSession_eventWithLongRunningToolIds_isRejected() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user"},""" +
            """{"id":"e2","author":"user","long_running_tool_ids":["t1"]}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("event 1")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventClaimingAnAdkFunction_isRejected() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"agent","content":{"role":"model","parts":""" +
            """[{"functionCall":{"name":"adk_request_confirmation","args":{}}}]}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventWithReservedFunctionResponse_isRejected() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"user","content":{"role":"user","parts":""" +
            """[{"functionResponse":{"name":"adk_request_confirmation","response":{}}}]}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("ADK protocol function")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventClaimingAdkRequestInput_isRejected() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"agent","content":{"role":"model","parts":""" +
            """[{"functionCall":{"name":"adk_request_input","args":{}}}]}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("ADK protocol function")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventClaimingAdkRequestCredential_isRejected() = testApplication {
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"events":[{"id":"e1","author":"agent","content":{"role":"model","parts":""" +
            """[{"functionCall":{"name":"adk_request_credential","args":{}}}]}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    assertThat(response.bodyAsText()).contains("ADK protocol function")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_nonJsonContentType_isRejected() = testApplication {
    // A text/plain POST skips the browser's CORS preflight; rejecting a non-empty non-JSON body
    // with 415 keeps a web page from seeding a session cross-site. The content is valid JSON, so
    // this pins the Content-Type check, not parsing.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { textBody("""{"state":{"k":"v"}}""") }

    assertThat(response.status).isEqualTo(HttpStatusCode.UnsupportedMediaType)
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun createSession_eventWithAnOrdinaryToolCall_isAccepted() = testApplication {
    // Deliberate: a conversation that used tools has to be restorable.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"sessionId":"s1","events":[{"id":"e1","author":"agent","content":{"role":"model",""" +
            """"parts":[{"functionCall":{"name":"getWeather","args":{"city":"Paris"}}}]}}]}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("getWeather")
  }

  @Test
  fun createSession_partialSeedEvent_isNotStored() = testApplication {
    // `appendEvent` drops partials, so a client seeding one gets a 200 with the event absent.
    // Parity with the reference server rather than an oversight, so it is pinned here.
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody("""{"sessionId":"s1","events":[{"id":"e1","author":"user","partial":true}]}""")
      }
    val fetched = client.get("/apps/testApp/users/testUser/sessions/s1").bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fetched).doesNotContain("e1")
  }

  @Test
  fun createSession_appendFailingMidway_keepsTheSessionAndEarlierEvents() = testApplication {
    // Seeding is not atomic with creation, matching the reference server: the session survives with
    // the events appended so far. The failure surfaces as a throw because the test engine rethrows
    // what a real one would answer 500.
    val fakeService = FailingAppendSessionService(acceptCount = 2)
    application { sessionApp(fakeService) }

    val outcome = runCatching {
      client.post("/apps/testApp/users/testUser/sessions") {
        jsonBody(
          """{"sessionId":"s1","events":[{"id":"e1","author":"user"},""" +
            """{"id":"e2","author":"user"},{"id":"e3","author":"user"}]}"""
        )
      }
    }

    assertThat(outcome.isFailure).isTrue()
    assertThat(fakeService.createdSessions).hasSize(1)
    assertThat(fakeService.appended).isEqualTo(2)
  }

  @Test
  fun createSessionWithId_wrappedStateBody_seedsState() = testApplication {
    application { sessionApp(InMemorySessionService()) }

    val response =
      client.post("/apps/testApp/users/testUser/sessions/s1") {
        jsonBody("""{"state":{"topic":"weather"}}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("\"topic\":\"weather\"")
  }

  @Test
  fun createSessionWithId_bodySessionId_isIgnored() = testApplication {
    // The path names the session; a body id must not silently win.
    val fakeService = FakeSessionService()
    application { sessionApp(fakeService) }

    client.post("/apps/testApp/users/testUser/sessions/from-path") {
      jsonBody("""{"sessionId":"from-body"}""")
    }

    assertThat(fakeService.createdSessions.single().key.id).isEqualTo("from-path")
  }

  @Test
  fun createSessionWithId_duplicateId_returnsConflict() = testApplication {
    // Uses the real service: the conflict is the one InMemorySessionService raises, not a fake's.
    val sessionService = InMemorySessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(sessionService) }
    }

    val first = client.post("/apps/testApp/users/testUser/sessions/test-session")
    val second = client.post("/apps/testApp/users/testUser/sessions/test-session")

    assertThat(first.status).isEqualTo(HttpStatusCode.OK)
    assertThat(second.status).isEqualTo(HttpStatusCode.Conflict)
  }

  @Test
  fun createSessionWithId_unrelatedSessionFailure_isNotConflict() = testApplication {
    // Only a taken id is a conflict; any other SessionException must reach the caller instead.
    val failure = SessionException("Session storage unavailable")
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(FailingSessionService(failure)) }
    }

    val outcome = runCatching { client.post("/apps/testApp/users/testUser/sessions/test-session") }

    // No StatusPages is installed, so it surfaces as a throw, and coroutine stack-trace recovery
    // re-wraps it, so match on type and message rather than on identity.
    assertThat(outcome.exceptionOrNull()).isInstanceOf(SessionException::class.java)
    assertThat(outcome.exceptionOrNull()).hasMessageThat().isEqualTo(failure.message)
  }

  @Test
  fun createSession_bodyIdTheBackendRejects_is400() = testApplication {
    // The body's own id first reaches a backend validator here, and no StatusPages is installed,
    // so without translation an id like "a/b" would answer 500 where the reference server says 400.
    val service = IdRejectingSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(service) }
    }

    val response =
      client.post("/apps/testApp/users/testUser/sessions") { jsonBody("""{"sessionId":"a/b"}""") }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    // The rejected id is caller data, so it must not come back in the message.
    assertThat(response.bodyAsText()).doesNotContain("a/b")
    assertThat(service.createdSessions).isEmpty()
  }

  @Test
  fun getSession_existing_returnsSession() = testApplication {
    val fakeService = FakeSessionService()
    fakeService.createdSessions.add(
      Session(key = SessionKey(appName = "testApp", userId = "testUser", id = "test-session"))
    )
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions/test-session")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("\"id\":\"test-session\"")
  }

  @Test
  fun deleteSession_existing_removesSession() = testApplication {
    val fakeService = FakeSessionService()
    fakeService.createdSessions.add(
      Session(key = SessionKey(appName = "testApp", userId = "testUser", id = "test-session"))
    )
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.delete("/apps/testApp/users/testUser/sessions/test-session")

    assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
    assertThat(fakeService.deletedSessions).contains("test-session")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  private fun Application.sessionApp(sessionService: SessionService) {
    install(ContentNegotiation) { json(adkJson) }
    routing { sessionRoutes(sessionService) }
  }

  @Test
  fun extractSessionParams_allPresent_returnsSuccess() {
    val params =
      io.ktor.http.parametersOf(
        "appName" to listOf("testApp"),
        "userId" to listOf("testUser"),
        "sessionId" to listOf("testSession"),
      )
    val result = extractSessionParams(params)
    assertThat(result).isInstanceOf(SessionRoutesResult.Success::class.java)
    val success = result as SessionRoutesResult.Success
    assertThat(success.params.appName).isEqualTo("testApp")
    assertThat(success.params.userId).isEqualTo("testUser")
    assertThat(success.params.sessionId).isEqualTo("testSession")
  }

  @Test
  fun extractSessionParams_missingAppName_returnsError() {
    val params = io.ktor.http.parametersOf("userId" to listOf("testUser"))
    val result = extractSessionParams(params)
    assertThat(result).isInstanceOf(SessionRoutesResult.Error::class.java)
    val error = result as SessionRoutesResult.Error
    assertThat(error.error).isEqualTo(SessionRoutesErrors.ERR_MISSING_APP_NAME)
  }

  @Test
  fun extractSessionParams_missingSessionId_whenRequired_returnsError() {
    val params =
      io.ktor.http.parametersOf("appName" to listOf("testApp"), "userId" to listOf("testUser"))
    val result = extractSessionParams(params, requireSessionId = true)
    assertThat(result).isInstanceOf(SessionRoutesResult.Error::class.java)
    val error = result as SessionRoutesResult.Error
    assertThat(error.error).isEqualTo(SessionRoutesErrors.ERR_MISSING_SESSION_ID)
  }
}
