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

package com.google.adk.kt.memory.appsearch

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSchema.PropertyConfig
import androidx.appsearch.app.AppSearchSchema.StringPropertyConfig
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SCHEMA_TYPE = "Memory"
private const val PROP_TEXT = "text"
private const val PROP_AUTHOR = "author"
private const val PROP_TIMESTAMP = "timestamp"
private const val PROP_ENTRY_ID = "entryId"
private const val PROP_CONTENT_JSON = "contentJson"
private const val PROP_METADATA_JSON = "customMetadataJson"

/** Max results returned by a single [search] call (v1 returns the first page only). */
private const val MAX_RESULTS = 25

/** Schema version; bump this and add a `Migrator` when making a backward-incompatible change. */
private const val SCHEMA_VERSION = 1

/** The AppSearch schema for a stored memory: only [PROP_TEXT] is indexed for full-text search. */
private val MEMORY_SCHEMA: AppSearchSchema =
  AppSearchSchema.Builder(SCHEMA_TYPE)
    .addProperty(indexedStringProperty(PROP_TEXT))
    .addProperty(unindexedStringProperty(PROP_AUTHOR))
    .addProperty(unindexedStringProperty(PROP_TIMESTAMP))
    .addProperty(unindexedStringProperty(PROP_ENTRY_ID))
    .addProperty(unindexedStringProperty(PROP_CONTENT_JSON))
    .addProperty(unindexedStringProperty(PROP_METADATA_JSON))
    .build()

private fun indexedStringProperty(name: String): StringPropertyConfig =
  StringPropertyConfig.Builder(name)
    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
    .setIndexingType(StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
    .build()

private fun unindexedStringProperty(name: String): StringPropertyConfig =
  StringPropertyConfig.Builder(name)
    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
    .setIndexingType(StringPropertyConfig.INDEXING_TYPE_NONE)
    .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_NONE)
    .build()

/**
 * [MemoryIndex] backed by AndroidX AppSearch LocalStorage in the app's private storage.
 *
 * The [AppSearchSession] is created (and its schema set) lazily on first use, guarded by a [Mutex];
 * once open, the thread-safe session is used without the lock. This is a thin adapter with no
 * business logic; the mapping and filtering behavior lives in [AppSearchMemoryService].
 */
internal class LocalStorageMemoryIndex(
  private val context: Context,
  private val databaseName: String,
) : MemoryIndex {

  // Guards only lazy session creation (see [session]); reads/writes on an open AppSearchSession are
  // thread-safe, so put/search run concurrently without holding this lock.
  private val mutex = Mutex()
  private var session: AppSearchSession? = null

  override suspend fun put(records: List<MemoryRecord>) {
    val documents = records.map { it.toGenericDocument() }
    val result =
      session()
        .putAsync(PutDocumentsRequest.Builder().addGenericDocuments(documents).build())
        .await()
    check(result.isSuccess) { "AppSearch put failed: ${result.failures.values}" }
  }

  override suspend fun search(namespace: String, query: String): List<MemoryRecord> {
    val spec =
      SearchSpec.Builder()
        .addFilterNamespaces(namespace)
        .addFilterSchemas(SCHEMA_TYPE)
        // Prefix term matching pairs with the text property's INDEXING_TYPE_PREFIXES so a partial
        // query term (e.g. "berl") matches an indexed token ("berlin").
        .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
        .setResultCountPerPage(MAX_RESULTS)
        .build()
    return session().search(query, spec).use { results ->
      results.nextPageAsync.await().map { it.genericDocument.toMemoryRecord() }
    }
  }

  override fun close() {
    // Terminal cleanup; not mutex-guarded because callers must not use the index concurrently with
    // close(). Mirrors the non-suspending close() on other ADK Android services.
    session?.close()
    session = null
  }

  /**
   * Returns the cached session, creating it and setting the schema on first use. Only this lazy
   * initialization is serialized by [mutex]; once open, the session is used without the lock.
   */
  private suspend fun session(): AppSearchSession = mutex.withLock {
    session?.let {
      return@withLock it
    }
    val created =
      LocalStorage.createSearchSessionAsync(
          LocalStorage.SearchContext.Builder(context, databaseName).build()
        )
        .await()
    created
      .setSchemaAsync(
        // forceOverride deletes documents incompatible with a changed schema, which is fine for
        // additive changes. For a backward-incompatible change, bump [SCHEMA_VERSION] and add a
        // Migrator (https://developer.android.com/reference/androidx/appsearch/app/Migrator)
        // instead of relying on the wipe.
        SetSchemaRequest.Builder()
          .addSchemas(MEMORY_SCHEMA)
          .setForceOverride(true)
          .setVersion(SCHEMA_VERSION)
          .build()
      )
      .await()
    session = created
    created
  }
}

internal fun MemoryRecord.toGenericDocument(): GenericDocument {
  val builder =
    GenericDocument.Builder<GenericDocument.Builder<*>>(namespace, id, SCHEMA_TYPE)
      .setPropertyString(PROP_TEXT, text)
      .setPropertyString(PROP_CONTENT_JSON, contentJson)
      .setPropertyString(PROP_METADATA_JSON, customMetadataJson)
  author?.let { builder.setPropertyString(PROP_AUTHOR, it) }
  timestamp?.let { builder.setPropertyString(PROP_TIMESTAMP, it) }
  entryId?.let { builder.setPropertyString(PROP_ENTRY_ID, it) }
  return builder.build()
}

internal fun GenericDocument.toMemoryRecord(): MemoryRecord =
  MemoryRecord(
    namespace = namespace,
    id = id,
    text = getPropertyString(PROP_TEXT).orEmpty(),
    author = getPropertyString(PROP_AUTHOR),
    timestamp = getPropertyString(PROP_TIMESTAMP),
    entryId = getPropertyString(PROP_ENTRY_ID),
    contentJson = getPropertyString(PROP_CONTENT_JSON) ?: "{}",
    customMetadataJson = getPropertyString(PROP_METADATA_JSON) ?: "{}",
  )
