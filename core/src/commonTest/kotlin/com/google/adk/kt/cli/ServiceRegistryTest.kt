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

package com.google.adk.kt.cli

import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.sessions.InMemorySessionService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ServiceRegistryTest {

  @Test
  fun processRegistry_memoryScheme_buildsAllThreeInMemoryBackends() {
    val registry = getServiceRegistry()
    assertIs<InMemorySessionService>(registry.createSessionService("memory://"))
    assertIs<InMemoryArtifactService>(registry.createArtifactService("memory://"))
    assertIs<InMemoryMemoryService>(registry.createMemoryService("memory://"))
  }

  @Test
  fun processRegistry_isTheSameInstanceEveryTime() {
    // A registration made while the process starts up has to be visible to a later lookup.
    assertSame(getServiceRegistry(), getServiceRegistry())
  }

  @Test
  fun createSessionService_unregisteredScheme_isNull() {
    assertNull(ServiceRegistry().createSessionService("mystore://host/path"))
  }

  @Test
  fun createSessionService_stringWithNoScheme_isNull() {
    val registry = ServiceRegistry()
    registry.registerSessionService("memory") { InMemorySessionService() }
    assertNull(registry.createSessionService("/var/lib/sessions"))
    assertNull(registry.createSessionService(":leading-colon"))
    assertNull(registry.createSessionService("9store://host"))
  }

  @Test
  fun createSessionService_uppercaseSchemeInTheUri_stillMatches() {
    val registry = ServiceRegistry()
    val service = InMemorySessionService()
    registry.registerSessionService("mystore") { service }
    assertSame(service, registry.createSessionService("MyStore://host"))
  }

  @Test
  fun createSessionService_schemeRegisteredWithACapital_neverMatches() {
    // Documented consequence of reading the scheme lowercased: registering "MyStore" is a
    // registration nothing can reach.
    val registry = ServiceRegistry()
    registry.registerSessionService("MyStore") { InMemorySessionService() }
    assertNull(registry.createSessionService("mystore://host"))
  }

  @Test
  fun createSessionService_handsTheFactoryTheWholeUri() {
    val registry = ServiceRegistry()
    var seen: String? = null
    registry.registerSessionService("mystore") { uri ->
      seen = uri
      InMemorySessionService()
    }
    val unused = registry.createSessionService("mystore://host/path?a=b")
    assertEquals("mystore://host/path?a=b", seen)
  }

  @Test
  fun registerSessionService_sameSchemeTwice_theSecondRegistrationAnswers() {
    val registry = ServiceRegistry()
    val first = InMemorySessionService()
    val second = InMemorySessionService()
    registry.registerSessionService("mystore") { first }
    registry.registerSessionService("mystore") { second }
    assertSame(second, registry.createSessionService("mystore://host"))
  }

  @Test
  fun registerSessionService_doesNotRegisterTheSchemeForTheOtherKinds() {
    val registry = ServiceRegistry()
    registry.registerSessionService("mystore") { InMemorySessionService() }
    assertNull(registry.createArtifactService("mystore://host"))
    assertNull(registry.createMemoryService("mystore://host"))
  }
}
