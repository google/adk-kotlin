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

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService

/**
 * Builds one backend service from the URI that names it.
 *
 * Register an implementation under a URI scheme with [ServiceRegistry] and every URI of that scheme
 * is built by it, so a backend the SDK has never heard of is reachable by a URI on a command line.
 */
fun interface ServiceFactory<out T : Any> {
  /**
   * @param uri the whole URI, scheme included. Everything after the scheme is the address of the
   *   backend, so a factory that needs a host, a path or a query parameter reads it from here.
   */
  operator fun invoke(uri: String): T
}

/**
 * Which session, artifact or memory backend a URI builds.
 *
 * The three kinds of service keep three separate scheme tables, so `mystore://` may name a session
 * backend without also naming an artifact one, and registering a scheme a second time replaces the
 * first registration. A URI whose scheme nobody registered, and a string with no scheme at all,
 * both build nothing, so callers can fall back on that null rather than having to catch.
 */
class ServiceRegistry {
  private val sessionFactories = concurrentMutableMapOf<String, ServiceFactory<SessionService>>()
  private val artifactFactories = concurrentMutableMapOf<String, ServiceFactory<ArtifactService>>()
  private val memoryFactories = concurrentMutableMapOf<String, ServiceFactory<MemoryService>>()

  /**
   * Registers [factory] as the builder for session URIs of [scheme], e.g. `mystore`.
   *
   * A URI's scheme is read lowercased, so a scheme registered with a capital in it is never
   * matched.
   */
  fun registerSessionService(scheme: String, factory: ServiceFactory<SessionService>) {
    sessionFactories[scheme] = factory
  }

  /** Registers [factory] as the builder for artifact URIs of [scheme]. */
  fun registerArtifactService(scheme: String, factory: ServiceFactory<ArtifactService>) {
    artifactFactories[scheme] = factory
  }

  /** Registers [factory] as the builder for memory URIs of [scheme]. */
  fun registerMemoryService(scheme: String, factory: ServiceFactory<MemoryService>) {
    memoryFactories[scheme] = factory
  }

  /** Builds the session backend [uri] names, or null if no factory claims its scheme. */
  fun createSessionService(uri: String): SessionService? = build(sessionFactories, uri)

  /** Builds the artifact backend [uri] names, or null if no factory claims its scheme. */
  fun createArtifactService(uri: String): ArtifactService? = build(artifactFactories, uri)

  /** Builds the memory backend [uri] names, or null if no factory claims its scheme. */
  fun createMemoryService(uri: String): MemoryService? = build(memoryFactories, uri)

  private fun <T : Any> build(factories: Map<String, ServiceFactory<T>>, uri: String): T? {
    val scheme = schemeOf(uri) ?: return null
    return factories[scheme]?.invoke(uri)
  }
}

/**
 * The registry the process shares, built on first use with the built-in schemes already in it, so a
 * registration made while the process starts up is visible wherever services are built from URIs.
 *
 * `memory://` is seeded for all three kinds of service and is the only built-in scheme here; the
 * rest of the ones the Python ADK seeds each need a driver, a bucket or credentials at
 * construction, so register those yourself.
 */
fun getServiceRegistry(): ServiceRegistry = processServiceRegistry

private val processServiceRegistry: ServiceRegistry by lazy {
  ServiceRegistry().apply {
    registerSessionService("memory") { InMemorySessionService() }
    registerArtifactService("memory") { InMemoryArtifactService() }
    registerMemoryService("memory") { InMemoryMemoryService() }
  }
}

/**
 * Reads the scheme off the front of [uri], lowercased, or null when it has none.
 *
 * Read off the string rather than through a URI parser on purpose: `memory://` is the URI the CLI
 * uses when no backend was asked for, and it has an empty authority, which RFC 3986 permits but
 * several parsers reject.
 */
private fun schemeOf(uri: String): String? {
  val colon = uri.indexOf(':')
  if (colon <= 0) return null
  val scheme = uri.substring(0, colon)
  if (!scheme[0].isAsciiLetter()) return null
  if (!scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) {
    return null
  }
  return scheme.lowercase()
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
