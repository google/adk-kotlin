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

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.AppFunctionState
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.isToolSelected
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exposes Android AppFunctions to an agent as ADK [BaseTool]s.
 *
 * An app discovers and executes the functions it declares itself with no permission, which is the
 * default here. Pass the toolset straight to an `LlmAgent`'s `toolsets`:
 * ```
 * val agent = LlmAgent(name = "notes", model = model, toolsets = listOf(AppFunctionsToolset(context)))
 * ```
 *
 * AppFunctions needs Android 16, or Android 14 on a device shipping the AppFunctions extension
 * library; a device without it contributes no tools rather than failing, so an agent can carry this
 * toolset without gating on the version itself. A function is likewise absent for a short while
 * after its app is installed or updated, until the platform has indexed it.
 *
 * A function that answers with a screen to open rather than data is not offered, since the model
 * cannot read a `PendingIntent` and nothing here can act on one.
 *
 * ADK declares `androidx.appfunctions` as `compileOnly`, so an app using this toolset must add that
 * dependency itself.
 */
@ExperimentalAppFunctionsFeature
class AppFunctionsToolset
internal constructor(
  private val client: AppFunctionClient,
  private val filteredPackageNames: Set<String>?,
  private val toolFilter: ToolFilter?,
) : Toolset {

  /**
   * @param context any [Context]; only its application context is retained.
   * @param filteredPackageNames the apps whose functions are offered, the calling app by default;
   *   `null` filters nothing and so offers every app's, and an empty set offers none. The nullable
   *   mirrors [AppFunctionSearchSpec.packageNames], where `null` likewise means unfiltered and an
   *   empty set is rejected. Reaching another app also needs the `EXECUTE_APP_FUNCTIONS` permission
   *   and that this app can see that package; short of that `null` yields the calling app's own
   *   functions and naming an app it cannot see contributes nothing. Executing another app's
   *   function can additionally require that this caller be allowlisted.
   * @param toolFilter selects which of the discovered functions the model is shown. It matches the
   *   rewritten, model-facing name, not the AppFunction identifier.
   */
  @JvmOverloads
  constructor(
    context: Context,
    filteredPackageNames: Set<String>? = setOf(context.packageName),
    toolFilter: ToolFilter? = null,
  ) : this(PlatformAppFunctionClient(context.applicationContext), filteredPackageNames, toolFilter)

  /**
   * Discovers the app functions on the device and offers each as a tool.
   *
   * The result is reused for the rest of the invocation, because this is called again for every
   * streamed chunk of the model's reply and each discovery is a platform query plus a full schema
   * conversion. A later turn discovers afresh, which is what picks up an app that has just been
   * installed, or one that registered a function while it was in the foreground.
   */
  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    // The SDK_INT test is what lets lint narrow for AppFunctionData, which does not exist before
    // this level; `isSupported` catches the rest -- a profile user, or 34/35 with no extension
    // library.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !client.isSupported) {
      if (warnedUnsupported.compareAndSet(false, true)) {
        logger.warn { "App functions are not available on this device; offering no tools." }
      }
      return emptyList()
    }
    // AppFunctionSearchSpec throws on an empty set, where null means every package.
    if (filteredPackageNames?.isEmpty() == true) {
      if (warnedNoPackages.compareAndSet(false, true)) {
        logger.warn { "The package name filter is empty; offering no tools." }
      }
      return emptyList()
    }

    val tools = discoverForInvocation(readonlyContext?.invocationId)
    return tools.filter { toolFilter.isToolSelected(it, readonlyContext) }
  }

  /** Discovers once per invocation, or on every call when there is no invocation to key on. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun discoverForInvocation(invocationId: String?): List<BaseTool> {
    if (invocationId == null) return discover()
    return cacheLock.withLock {
      val cached = cache
      if (cached != null && cached.invocationId == invocationId) cached.tools
      else {
        // A discovery already running when close() lands must not put the cache back.
        val before = closes.get()
        discover().also { if (closes.get() == before) cache = Cache(invocationId, it) }
      }
    }
  }

  /** Reads the platform's functions and converts each one the model can be shown. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun discover(): List<BaseTool> {
    val discovered =
      try {
        client.search(AppFunctionSearchSpec(packageNames = filteredPackageNames))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Discovery reaches AppSearch and the platform, either of which can fail. Failing here
        // would abort the whole turn and take every other toolset's tools with it. An
        // AppFunctionException's message is the app's own text, so only its category is logged.
        if (e is AppFunctionException) logger.warn { "Discovery failed: ${e.category()}." }
        else logger.warn { "Could not read the device's app functions." }
        return emptyList()
      }

    val disabled = disabledAmong(discovered)

    // Sorted so that a name never depends on the order the platform happened to return.
    val ordered = discovered.sortedWith(compareBy({ it.packageName }, { it.id }))
    // From the configuration, not from what came back: a list that may hold two packages qualifies
    // by package even where only one of them is installed, so no name changes when the other one
    // arrives.
    val qualifyByPackage = filteredPackageNames == null || filteredPackageNames.size > 1
    val names = mutableSetOf<String>()
    val tools = mutableListOf<BaseTool>()
    // Skips are counted rather than named: a function id carries the app's package, so naming one
    // would record which apps are installed in a durable log.
    var disabledCount = 0
    var screenOnlyCount = 0
    for (metadata in ordered) {
      // An app turns a function off to tell the agent it is unavailable, so a disabled one is not
      // offered. The metadata's own `isEnabled` is no use here -- its getter is
      // @RestrictTo(LIBRARY_GROUP) and the SDK hardcodes it to false regardless.
      if (metadata.name in disabled) {
        disabledCount++
        continue
      }
      // A screen is for the app to open, not a value the model can read.
      if (AppFunctionTypes.isPendingIntent(metadata.response.valueType, metadata.components)) {
        screenOnlyCount++
        continue
      }
      val name = uniqueToolName(metadata.packageName, metadata.id, names, qualifyByPackage)
      val declaration = AppFunctionSchemaConverter.toFunctionDeclaration(metadata, name) ?: continue
      names.add(name)
      tools.add(AppFunctionTool(metadata, declaration, client))
    }
    // Counts, because the case a developer actually hits is an empty result: a typo in
    // `filteredPackageNames`, a missing permission, or an app the platform has not indexed yet.
    logger.info {
      "Offering ${tools.size} of ${discovered.size} app functions as tools " +
        "($disabledCount disabled, $screenOnlyCount screen-only)."
    }
    return tools
  }

  /**
   * The names among [discovered] the app has switched off, which is how it tells an agent a
   * function is unavailable.
   *
   * Only a function the platform reports as disabled counts. One it omits is not visible to this
   * caller rather than off, and a failed query says nothing about any of them, so both leave the
   * function offered rather than silently dropping it.
   */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun disabledAmong(discovered: List<AppFunctionMetadata>): Set<AppFunctionName> {
    // A device that declares nothing is the common case; asking about an empty list costs a
    // binder round trip per turn and can only answer nothing.
    if (discovered.isEmpty()) return emptySet()
    return try {
      client
        .states(discovered.map { it.name })
        .filterNot { it.isEnabled }
        .mapTo(mutableSetOf()) { it.functionName }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (e is AppFunctionException) logger.warn { "Reading states failed: ${e.category()}." }
      else logger.warn { "Could not read the app functions' states." }
      emptySet()
    }
  }

  /** Drops the cached tools, which hold the full metadata of everything last discovered. */
  override fun close() {
    closes.incrementAndGet()
    cache = null
  }

  /** The tools last discovered and the invocation they were discovered for. */
  private class Cache(val invocationId: String, val tools: List<BaseTool>)

  private val cacheLock = Mutex()

  /**
   * A single volatile reference because [close] is not `suspend` and so cannot take [cacheLock].
   * Swapping one object keeps that write visible and keeps the id and the tools from disagreeing.
   */
  @Volatile private var cache: Cache? = null

  /**
   * Counts the calls to [close], so a discovery already in flight can see one landed behind it and
   * leave [cache] alone. A count rather than a flag, because a toolset shared by two agents
   * outlives the first `Runner.close()` that reaches it, and one that could never cache again would
   * re-query the platform for every streamed chunk from then on.
   */
  private val closes = AtomicLong()

  /** Both keep their warning to one per toolset, since [getTools] runs for every streamed chunk. */
  private val warnedUnsupported = AtomicBoolean(false)
  private val warnedNoPackages = AtomicBoolean(false)

  private companion object {
    private val logger = LoggerFactory.getLogger(AppFunctionsToolset::class)
  }
}

/**
 * The AppFunctions platform calls the toolset makes.
 *
 * [AppFunctionManager] is final and reaching it needs a device, so the calls sit behind this seam
 * and tests substitute their own.
 */
internal interface AppFunctionClient {
  /** Whether this device and user profile have AppFunctions at all. */
  val isSupported: Boolean

  /** Returns the functions matching [spec], or nothing when the device has no AppFunctions. */
  suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata>

  /** Returns the runtime state of [names], omitting any the caller cannot see. */
  suspend fun states(names: List<AppFunctionName>): List<AppFunctionState>

  /** Executes [request], or returns `null` when the device has no AppFunctions. */
  suspend fun execute(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse?
}

/** The [AppFunctionClient] backed by the platform, holding the application context. */
internal class PlatformAppFunctionClient(context: Context) : AppFunctionClient {

  /**
   * `null` on a device or user profile where AppFunctions are unavailable.
   *
   * Resolved once: what it depends on -- the platform version and whether this user is a profile --
   * cannot change while the process lives. The toolset warns for this case, so nothing is logged
   * here; doing both warns twice for one device.
   */
  private val manager: AppFunctionManager? by lazy { AppFunctionManager.getInstance(context) }

  override val isSupported: Boolean
    get() = manager != null

  override suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata> =
    manager?.searchAppFunctions(spec).orEmpty()

  override suspend fun states(names: List<AppFunctionName>): List<AppFunctionState> =
    manager?.getAppFunctionStates(names).orEmpty()

  override suspend fun execute(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse? =
    manager?.executeAppFunction(request)
}

/** Longest function name the model accepts. */
private const val MAX_TOOL_NAME_LENGTH = 64

/**
 * Characters the class part of an over-long name may contribute, half the budget less the
 * separator, leaving the same for the method.
 *
 * Fixed rather than whatever the method leaves over, so every method of one class is trimmed to the
 * same prefix and the model can reuse a prefix it has already seen. An even split because both
 * sides are load-bearing: the class tail is what keeps two apps' functions apart, and the method
 * tail is what keeps a class's own functions apart.
 */
private const val CLASS_PREFIX_LENGTH = (MAX_TOOL_NAME_LENGTH - 1) / 2

/**
 * The model-facing name for the function [id] of [packageName], distinct from every name in
 * [taken].
 *
 * An AppFunction identifier is a qualified class name and a method joined by `#`, which the model's
 * function-name grammar does not allow, so it is rewritten rather than passed through. The method
 * name alone is what an app's own guidance calls it, and it is the cheapest thing to send, so it is
 * preferred; [qualifyByPackage] widens that when one tool list can hold two apps' functions.
 */
internal fun uniqueToolName(
  packageName: String,
  id: String,
  taken: Set<String>,
  qualifyByPackage: Boolean,
): String {
  val raw = if (id.startsWith("$packageName.")) id else "$packageName.$id"
  // Split before sanitizing, since `#` becomes an underscore indistinguishable from the rest.
  val className = sanitize(raw.substringBeforeLast('#'))
  val method = sanitize(raw.substringAfterLast('#', missingDelimiterValue = ""))
  // Widening comes from the configuration, never from what discovery returned: a list that could
  // hold two packages qualifies even on a device where only one of them is installed, so the name
  // does not change the day the second app arrives.
  val candidates =
    if (qualifyByPackage || method.isEmpty()) listOf(fit(className, method))
    else listOf(fit("", method), fit(className.substringAfterLast('_'), method))
  candidates
    .firstOrNull { it !in taken }
    ?.let {
      return it
    }
  // Two ids that qualify the same way and sanitize the same way: nothing is left but a counter.
  val widest = candidates.last()
  return generateSequence(2) { it + 1 }
    .map { "${widest.take(MAX_TOOL_NAME_LENGTH - "_$it".length)}_$it" }
    .first { it !in taken }
}

/** [raw] reduced to the characters a model-facing name may contain. */
private fun sanitize(raw: String): String =
  raw.map { if (it.isNameChar()) it else '_' }.joinToString("")

/**
 * [className] and [method] joined within [MAX_TOOL_NAME_LENGTH] minus [reserve], starting with a
 * letter or underscore.
 *
 * Each side is trimmed to its own budget rather than the pair to a shared one, so two methods of
 * one class keep the same prefix; trimming the joined name instead would give them different ones
 * and leave the model unable to reuse a prefix it has already seen. Both keep their tail, which is
 * what distinguishes a class from its package and a method from its siblings. A method longer than
 * the budget left to it keeps only its last characters. An empty [className] gives the method the
 * whole budget, which is how an unqualified name is produced.
 */
private fun fit(className: String, method: String, reserve: Int = 0): String {
  val limit = MAX_TOOL_NAME_LENGTH - reserve
  if (method.isEmpty()) {
    // With no method the whole name is the class, so the first character of *that* is the one
    // that has to be legal -- the class prefix below is not what gets returned here.
    val whole = className.takeLast(limit)
    return if (whole.firstOrNull().isNameStart()) whole else "_" + className.takeLast(limit - 1)
  }
  if (className.isEmpty()) {
    // The mirror of the case above: the method is the whole name, so it is charged no class prefix
    // and no separator, and it is its first character that has to be legal.
    val whole = method.takeLast(limit)
    return if (whole.firstOrNull().isNameStart()) whole else "_" + method.takeLast(limit - 1)
  }
  val classPart = className.takeLast(CLASS_PREFIX_LENGTH)
  // A leading underscore is charged to the method, never to the class: taking it from the class
  // would drop the character telling two classes apart, and vary the prefix by method length.
  val lead = if (classPart.firstOrNull().isNameStart()) "" else "_"
  val methodPart = method.takeLast((limit - lead.length - CLASS_PREFIX_LENGTH - 1).coerceAtLeast(0))
  return "$lead${classPart}_$methodPart"
}

/**
 * Whether the model's function-name grammar accepts this character.
 *
 * A dot is legal in that grammar but is deliberately excluded: with a dotted name the model
 * intermittently emits a call the backend rejects as malformed, which never happened with the
 * underscored form.
 */
private fun Char.isNameChar(): Boolean =
  this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

private fun Char?.isNameStart(): Boolean =
  this != null && (this in 'a'..'z' || this in 'A'..'Z' || this == '_')
