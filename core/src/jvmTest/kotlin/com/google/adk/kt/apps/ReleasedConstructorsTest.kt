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

package com.google.adk.kt.apps

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.ContextFrameworkData
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.InvocationCostManager
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Code built against 1.1.0 must keep working here. Kotlin binaries link to the exact constructor
 * descriptors below, including the synthetic one that fills in default arguments. Java source
 * recompiled against this version must bind to the same constructors, which only javac can check.
 */
class ReleasedConstructorsTest {

  @Test
  fun app_releasedConstructorWithDefaults_stillLinksAndLeavesRootNodeUnset() {
    // Arrange: 1.1.0 `App(appName, rootAgent)`, compiled with every later argument defaulted.
    val agent = DummyAgent(name = "root")
    val constructor =
      App::class
        .java
        .getConstructor(
          *RELEASED_APP_PARAMETERS,
          Int::class.java,
          DefaultConstructorMarker::class.java,
        )

    // Act
    val app = constructor.newInstance("my_app", agent, null, null, null, null, 0b111100, null)

    // Assert
    assertEquals("my_app", app.appName)
    assertSame(agent, app.rootAgent)
    assertEquals(emptyList(), app.plugins)
    assertNull(app.rootNode)
  }

  @Test
  fun app_releasedFullConstructor_stillLinks() {
    // Arrange
    val agent = DummyAgent(name = "root")
    val resumability = ResumabilityConfig(isResumable = true)

    // Act
    val app =
      App::class
        .java
        .getConstructor(*RELEASED_APP_PARAMETERS)
        .newInstance("my_app", agent, emptyList<Any>(), resumability, null, null)

    // Assert
    assertSame(resumability, app.resumabilityConfig)
    assertNull(app.rootNode)
  }

  @Test
  fun app_javaSourceWrittenAgainstTheReleasedApi_bindsToTheAgentConstructor() {
    // Arrange
    val agent = DummyAgent(name = "root")
    val caller =
      compileJava(
        "ReleasedAppCaller",
        """
        import com.google.adk.kt.agents.BaseAgent;
        import com.google.adk.kt.apps.App;
        import java.util.List;

        public final class ReleasedAppCaller {
          public static App create(BaseAgent agent) {
            return new App("my_app", agent, List.of(), null, null, null);
          }
        }
        """,
      )

    // Act
    val app = caller.getMethod("create", BaseAgent::class.java).invoke(null, agent) as App

    // Assert: `BaseAgent` is also a `Node`, so a wrong binding wraps the agent in a node view.
    assertSame(agent, app.rootAgent)
    assertNull(app.rootNode)
  }

  @Test
  fun invocationContext_releasedConstructorWithDefaults_stillLinksAndLeavesNodeUnset() {
    // Arrange: 1.1.0 `InvocationContext(session, agent = agent, branch = "b")`, compiled with the
    // other arguments defaulted. Bit i of the mask marks parameter i as defaulted.
    val session = testSession()
    val agent = DummyAgent(name = "root")
    val allDefaulted = (1 shl RELEASED_CONTEXT_PARAMETERS.size) - 1
    val mask = allDefaulted and 0b1101.inv()
    val arguments = arrayOfNulls<Any>(RELEASED_CONTEXT_PARAMETERS.size)
    arguments[0] = session
    arguments[2] = agent
    arguments[3] = "b"
    arguments[16] = false
    val constructor =
      InvocationContext::class
        .java
        .getConstructor(
          *RELEASED_CONTEXT_PARAMETERS,
          Int::class.java,
          DefaultConstructorMarker::class.java,
        )

    // Act
    val context = constructor.newInstance(*arguments, mask, null)

    // Assert
    assertSame(session, context.session)
    assertSame(agent, context.agent)
    assertEquals("b", context.branch)
    assertNull(context.node)
  }

  @Test
  fun invocationContext_javaSourceWrittenAgainstTheReleasedApi_compiles() {
    // Arrange
    val session = testSession()
    val agent = DummyAgent(name = "root")
    val caller =
      compileJava(
        "ReleasedContextCaller",
        """
        import com.google.adk.kt.agents.BaseAgent;
        import com.google.adk.kt.agents.ContextFrameworkData;
        import com.google.adk.kt.agents.InvocationContext;
        import com.google.adk.kt.agents.InvocationCostManager;
        import com.google.adk.kt.plugins.PluginManager;
        import com.google.adk.kt.sessions.Session;
        import java.util.HashMap;

        public final class ReleasedContextCaller {
          public static InvocationContext create(
              Session session,
              BaseAgent agent,
              ContextFrameworkData frameworkData,
              PluginManager pluginManager) {
            return new InvocationContext(
                session, null, agent, "b", "inv-1", null, null, null, null, null, null, null,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), frameworkData, false,
                pluginManager, new InvocationCostManager());
          }
        }
        """,
      )

    // Act
    val context =
      caller
        .getMethod(
          "create",
          Session::class.java,
          BaseAgent::class.java,
          ContextFrameworkData::class.java,
          PluginManager::class.java,
        )
        .invoke(null, session, agent, ContextFrameworkData(), PluginManager()) as InvocationContext

    // Assert
    assertSame(agent, context.agent)
    assertEquals("b", context.branch)
    assertEquals("inv-1", context.invocationId)
    assertNull(context.node)
  }

  /** Compiles [source] with javac against the test classpath and loads [className]. */
  private fun compileJava(className: String, source: String): Class<*> {
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "javac is unavailable." }
    val directory = createTempDirectory("released-api-java")
    val sourceFile = directory.resolve("$className.java").apply { writeText(source.trimIndent()) }
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
    assertTrue(exitCode == 0, "javac failed:\n$errors")
    return URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader)
      .loadClass(className)
  }

  private companion object {
    val RELEASED_APP_PARAMETERS: Array<Class<*>> =
      arrayOf(
        String::class.java,
        BaseAgent::class.java,
        List::class.java,
        ResumabilityConfig::class.java,
        EventsCompactionConfig::class.java,
        ContextCacheConfig::class.java,
      )

    val RELEASED_CONTEXT_PARAMETERS: Array<Class<*>> =
      arrayOf(
        Session::class.java,
        RunConfig::class.java,
        BaseAgent::class.java,
        String::class.java,
        String::class.java,
        ArtifactService::class.java,
        MemoryService::class.java,
        SessionService::class.java,
        ResumabilityConfig::class.java,
        EventsCompactionConfig::class.java,
        ContextCacheConfig::class.java,
        Content::class.java,
        Map::class.java,
        Map::class.java,
        Map::class.java,
        ContextFrameworkData::class.java,
        Boolean::class.java,
        PluginManager::class.java,
        InvocationCostManager::class.java,
      )
  }
}
