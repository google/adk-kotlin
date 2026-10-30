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

@file:OptIn(ExperimentalAppInfoFeature::class)
@file:Suppress("DEPRECATION") // Covers the AgentLoader path kept until 2.0.

package com.google.adk.kt.webserver

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** [AdkServerConfig.inMemory] is the one-line way to serve a single agent. */
@OptIn(FrameworkInternalApi::class, AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class AdkServerConfigTest {
  private val agent = FakeAgent()

  @Test
  fun inMemory_servesTheAgent() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }

    assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.OK)
    assertThat(client.get("/list-apps").bodyAsText()).contains(agent.name)
  }

  @Test
  fun inMemory_retainsSessionState() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }
    val session = "/apps/${agent.name}/users/u/sessions/s1"

    assertThat(client.post(session).status).isEqualTo(HttpStatusCode.OK)

    // A stateless session service would 404 here; the in-memory one hands the session back.
    assertThat(client.get(session).status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun inMemory_rejectsARunForAnUnknownAppName() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }

    // The single-agent loader used to answer with its agent whatever name was asked for.
    val response =
      client.post("/run") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody("""{"appName":"not-${agent.name}","userId":"u"}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun inMemory_retainsArtifactState() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }
    val artifacts = "/apps/${agent.name}/users/u/sessions/s1/artifacts"

    val saved =
      client.post(artifacts) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(adkJson.encodeToString(Part(fileData = FileData(displayName = "note.txt"))))
      }
    assertThat(saved.status).isEqualTo(HttpStatusCode.OK)

    // A write-dropping artifact service would hand back an empty list here.
    assertThat(client.get(artifacts).bodyAsText()).contains("note.txt")
  }

  @Test
  fun inMemory_defaultsThePort() {
    assertThat(AdkServerConfig.inMemory(agent).port).isEqualTo(AdkServerConfig.DEFAULT_PORT)
    assertThat(AdkServerConfig.inMemory(agent, port = 9123).port).isEqualTo(9123)
  }

  @Test
  fun inMemory_defaultsTheHostToLoopback() {
    // Runs everywhere: the socket probes in AdkServerLifecycleTest skip on a loopback-only host.
    assertThat(AdkServerConfig.inMemory(agent).host).isEqualTo("127.0.0.1")
  }

  @Test
  fun inMemory_leavesTheDevUiToTheServerVariant() {
    assertThat(AdkServerConfig.inMemory(agent).webUiEnabled).isNull()
  }

  @Test
  fun inMemory_app_servesTheApp() = testApplication {
    // Arrange
    val app = App(appName = "demo_app", rootAgent = agent)

    // Act
    application { adkApiModule(AdkServerConfig.inMemory(app)) }

    // Assert
    assertThat(client.get("/list-apps").bodyAsText()).isEqualTo("[\"demo_app\"]")
  }

  @Test
  fun inMemory_agentWithDottedName_servesItUnderThatName() = testApplication {
    // Arrange
    val dotted = FakeAgent("support.bot")

    // Act
    application { adkApiModule(AdkServerConfig.inMemory(dotted).copy(includeAppInfo = true)) }

    // Assert
    assertThat(client.get("/list-apps").bodyAsText()).isEqualTo("[\"support.bot\"]")
    assertThat(client.get("/apps/support.bot/app-info").status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun constructor_bothOrNeitherLoader_throws() {
    // Arrange
    val sessions = InMemorySessionService()
    val artifacts = InMemoryArtifactService()
    val apps = InMemoryAppLoader(App(appName = "demo_app", rootAgent = agent))

    // Act + Assert
    assertThrows(IllegalArgumentException::class.java) {
      AdkServerConfig(
        agentLoader = FakeAgentLoader(),
        sessionService = sessions,
        artifactService = artifacts,
        appLoader = apps,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdkServerConfig(sessionService = sessions, artifactService = artifacts)
    }
  }

  @Test
  fun builder_appLoader_roundTripsThroughToBuilder() {
    // Arrange
    val apps = InMemoryAppLoader(App(appName = "demo_app", rootAgent = agent))

    // Act
    val config =
      AdkServerConfig.builder()
        .appLoader(apps)
        .sessionService(InMemorySessionService())
        .artifactService(InMemoryArtifactService())
        .build()

    // Assert
    assertThat(config.appLoader).isSameInstanceAs(apps)
    assertThat(config.agentLoader).isNull()
    assertThat(config.toBuilder().build()).isEqualTo(config)
  }

  @Test
  fun builder_withoutALoader_throws() {
    // Arrange
    val builder =
      AdkServerConfig.builder()
        .sessionService(InMemorySessionService())
        .artifactService(InMemoryArtifactService())

    // Act + Assert
    assertThrows(IllegalArgumentException::class.java) { builder.build() }
  }

  @Test
  fun builder_appliesConstructorDefaultsForUnsetProperties() {
    val config =
      AdkServerConfig.builder()
        .appLoader(FakeAppLoader())
        .sessionService(InMemorySessionService())
        .artifactService(InMemoryArtifactService())
        .build()

    assertThat(config.port).isEqualTo(AdkServerConfig.DEFAULT_PORT)
    assertThat(config.host).isEqualTo(AdkServerConfig.DEFAULT_HOST)
    assertThat(config.captureMessageContent).isFalse()
    assertThat(config.plugins).isEmpty()
    assertThat(config.webUiEnabled).isNull()
    assertThat(config.includeAppInfo).isNull()
    assertThat(config.allowOrigins).isEmpty()
    assertThat(config.allowRemoteRequests).isNull()
  }

  @Test
  fun builder_appliesEveryProvidedValue() {
    val loader = FakeAgentLoader()
    val sessions = InMemorySessionService()
    val artifacts = InMemoryArtifactService()
    val exporter = ApiServerSpanExporter()

    val config =
      AdkServerConfig.builder()
        .agentLoader(loader)
        .sessionService(sessions)
        .artifactService(artifacts)
        .port(9123)
        .host("0.0.0.0")
        .apiServerSpanExporter(exporter)
        .captureMessageContent(true)
        .webUiEnabled(true)
        .includeAppInfo(true)
        .allowOrigins(listOf("https://console.example.com"))
        .allowRemoteRequests(true)
        .build()

    assertThat(config.agentLoader).isSameInstanceAs(loader)
    assertThat(config.sessionService).isSameInstanceAs(sessions)
    assertThat(config.artifactService).isSameInstanceAs(artifacts)
    assertThat(config.port).isEqualTo(9123)
    assertThat(config.host).isEqualTo("0.0.0.0")
    assertThat(config.apiServerSpanExporter).isSameInstanceAs(exporter)
    assertThat(config.captureMessageContent).isTrue()
    assertThat(config.webUiEnabled).isTrue()
    assertThat(config.includeAppInfo).isTrue()
    assertThat(config.allowOrigins).containsExactly("https://console.example.com")
    assertThat(config.allowRemoteRequests).isTrue()
  }

  @Test
  fun builder_requiresSessionAndArtifactServices() {
    assertThrows(IllegalStateException::class.java) { AdkServerConfig.builder().build() }
  }

  @Test
  fun toBuilder_matchesCopy() {
    val config =
      AdkServerConfig(
        agentLoader = FakeAgentLoader(),
        sessionService = InMemorySessionService(),
        artifactService = InMemoryArtifactService(),
        port = 9123,
        host = "0.0.0.0",
        apiServerSpanExporter = ApiServerSpanExporter(),
        captureMessageContent = true,
        plugins =
          listOf(
            object : Plugin {
              override val name = "test-plugin"
            }
          ),
        webUiEnabled = true,
        camelCaseEnforced = true,
        includeAppInfo = true,
        allowOrigins = listOf("https://console.example.com"),
        allowRemoteRequests = true,
      )

    assertThat(config.toBuilder().build()).isEqualTo(config.copy())
    assertThat(config.toBuilder().port(9124).build()).isEqualTo(config.copy(port = 9124))
  }

  @Test
  fun preGuardTwelveParameterConstructor_linksFromKotlinDefaultsAndCompilesInJava() {
    val appLoader = InMemoryAppLoader(App(appName = "test_app", rootAgent = FakeAgent()))
    val sessions = InMemorySessionService()
    val artifacts = InMemoryArtifactService()
    val twelveParamTypes =
      arrayOf(
        AgentLoader::class.java,
        SessionService::class.java,
        ArtifactService::class.java,
        Int::class.java,
        String::class.java,
        ApiServerSpanExporter::class.java,
        Boolean::class.java,
        List::class.java,
        Boolean::class.javaObjectType,
        Boolean::class.javaObjectType,
        Boolean::class.javaObjectType,
        AppLoader::class.java,
      )
    val kotlinDefaultConstructor =
      AdkServerConfig::class
        .java
        .getConstructor(*twelveParamTypes, Int::class.java, DefaultConstructorMarker::class.java)
    // Default every parameter except sessionService (1), artifactService (2), and appLoader (11).
    val mask = ((1 shl 12) - 1) and ((1 shl 1) or (1 shl 2) or (1 shl 11)).inv()
    val fromKotlinDefaults =
      kotlinDefaultConstructor.newInstance(
        null,
        sessions,
        artifacts,
        0,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        appLoader,
        mask,
        null,
      )
    assertThat(fromKotlinDefaults.appLoader).isSameInstanceAs(appLoader)
    assertThat(fromKotlinDefaults.captureMessageContent).isFalse()
    assertThat(fromKotlinDefaults.allowOrigins).isEmpty()
    assertThat(fromKotlinDefaults.allowRemoteRequests).isNull()

    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "javac is unavailable." }
    val directory = createTempDirectory("adk-server-config-java")
    try {
      val sourceFile =
        directory.resolve("TwelveArgCaller.java").apply {
          writeText(
            """
            import com.google.adk.kt.artifacts.ArtifactService;
            import com.google.adk.kt.sessions.SessionService;
            import com.google.adk.kt.webserver.AdkServerConfig;
            import com.google.adk.kt.webserver.loaders.AppLoader;
            import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter;
            import java.util.List;

            public final class TwelveArgCaller {
              public static AdkServerConfig create(
                  SessionService sessions, ArtifactService artifacts, AppLoader apps) {
                return new AdkServerConfig(
                    null, sessions, artifacts, 8080, "127.0.0.1", new ApiServerSpanExporter(),
                    false, List.of(), null, null, null, apps);
              }
            }
            """
              .trimIndent()
          )
        }
      val errors = ByteArrayOutputStream()
      val exitCode =
        compiler.run(
          null,
          null,
          errors,
          "-proc:none",
          "-classpath",
          System.getProperty("java.class.path"),
          "-d",
          directory.toString(),
          sourceFile.toString(),
        )
      assertWithMessage("javac failed:\n$errors").that(exitCode).isEqualTo(0)
      URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { classLoader ->
        val caller = classLoader.loadClass("TwelveArgCaller")
        val fromJava =
          caller
            .getMethod(
              "create",
              SessionService::class.java,
              ArtifactService::class.java,
              AppLoader::class.java,
            )
            .invoke(null, sessions, artifacts, appLoader) as AdkServerConfig
        assertThat(fromJava.appLoader).isSameInstanceAs(appLoader)
        assertThat(fromJava.allowOrigins).isEmpty()
        assertThat(fromJava.allowRemoteRequests).isNull()
      }
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
