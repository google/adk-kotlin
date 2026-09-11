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

import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFunctionTypesTest {

  @Test
  fun resolve_referenceChain_followsItToTheNamedType() {
    val components = components("A" to reference("B"), "B" to note())

    assertThat(AppFunctionTypes.resolve(reference("A"), components)).isEqualTo(note())
  }

  @Test
  fun resolve_referenceToNothing_returnsNull() {
    assertThat(AppFunctionTypes.resolve(reference("Missing"), components())).isNull()
  }

  @Test
  fun resolve_referenceCycle_returnsNull() {
    val components = components("A" to reference("B"), "B" to reference("A"))

    assertThat(AppFunctionTypes.resolve(reference("A"), components)).isNull()
  }

  @Test
  fun resolve_typeThatIsNotAReference_returnsItUnchanged() {
    assertThat(AppFunctionTypes.resolve(note(), components())).isEqualTo(note())
  }

  @Test
  fun flattenAllOf_objectMembers_mergesPropertiesAndRequired() {
    val merged =
      allOf(
        AppFunctionObjectTypeMetadata(
          properties = mapOf("city" to string()),
          required = listOf("city"),
          qualifiedName = "Address",
          isNullable = false,
        ),
        reference("Note"),
      )

    val flattened = AppFunctionTypes.flattenAllOf(merged, components("Note" to note()), budget())

    assertThat(flattened?.properties?.keys).containsExactly("city", "title")
    assertThat(flattened?.required).containsExactly("city", "title")
  }

  @Test
  fun flattenAllOf_anyComposition_headerFieldsMatchTheLibrarysPseudoObject() {
    // The app builds its spec from the library's pseudo object, which fixes both of these.
    val described =
      AppFunctionAllOfTypeMetadata(
        matchAll = listOf(note()),
        qualifiedName = "com.example.Merged",
        isNullable = true,
        description = "a described composition",
      )

    val flattened = AppFunctionTypes.flattenAllOf(described, components(), budget())

    assertThat(flattened?.isNullable).isFalse()
    assertThat(flattened?.description).isEmpty()
    assertThat(flattened?.qualifiedName).isEqualTo("com.example.Merged")
  }

  @Test
  fun flattenAllOf_budgetSharedAcrossCalls_stopsWhenItIsSpent() {
    // The budget is the caller's, not the call's, so a composition reached twice costs twice.
    val shared = intArrayOf(3)
    val composition = allOf(note(), note())

    assertThat(AppFunctionTypes.flattenAllOf(composition, components(), shared)).isNotNull()
    assertThat(AppFunctionTypes.flattenAllOf(composition, components(), shared)).isNull()
  }

  @Test
  fun flattenAllOf_laterMemberWithTheSameProperty_wins() {
    val first =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = emptyList(),
        qualifiedName = "First",
        isNullable = false,
      )
    val second =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to AppFunctionIntTypeMetadata(isNullable = false)),
        required = emptyList(),
        qualifiedName = "Second",
        isNullable = false,
      )

    val flattened = AppFunctionTypes.flattenAllOf(allOf(first, second), components(), budget())

    assertThat(flattened?.properties?.get("title"))
      .isEqualTo(AppFunctionIntTypeMetadata(isNullable = false))
  }

  @Test
  fun flattenAllOf_memberThatIsNotAnObject_contributesNothing() {
    // The app's own validation skips such a member rather than refusing the composition.
    val flattened = AppFunctionTypes.flattenAllOf(allOf(note(), string()), components(), budget())

    assertThat(flattened?.properties?.keys).containsExactly("title")
  }

  @Test
  fun flattenAllOf_referenceToAnotherReference_contributesNothing() {
    // The library follows a member exactly one hop, so a chain stops short of the object.
    val components = components("A" to reference("B"), "B" to note())

    val flattened = AppFunctionTypes.flattenAllOf(allOf(reference("A")), components, budget())

    assertThat(flattened?.properties).isEmpty()
  }

  @Test
  fun flattenAllOf_referenceToAnAllOf_contributesNothing() {
    val components = components("Inner" to allOf(note()))

    val flattened = AppFunctionTypes.flattenAllOf(allOf(reference("Inner")), components, budget())

    assertThat(flattened?.properties).isEmpty()
  }

  @Test
  fun flattenAllOf_unresolvableMember_returnsNull() {
    assertThat(AppFunctionTypes.flattenAllOf(allOf(reference("Missing")), components(), budget()))
      .isNull()
  }

  @Test
  fun flattenAllOf_nestedAllOf_flattensThrough() {
    val inner = allOf(note())
    val outer = allOf(inner)

    assertThat(AppFunctionTypes.flattenAllOf(outer, components(), budget())?.properties?.keys)
      .containsExactly("title")
  }

  @Test(timeout = 30_000)
  fun flattenAllOf_moreMembersThanTheBudget_returnsNull() {
    // One composition is re-merged on every path that reaches it, so the total is capped.
    val members = Array<AppFunctionDataTypeMetadata>(2_000) { note() }

    assertThat(AppFunctionTypes.flattenAllOf(allOf(*members), components(), budget())).isNull()
  }

  @Test(timeout = 30_000)
  fun flattenAllOf_nestedPastTheDepthCap_returnsNull() {
    var nested = allOf(note())
    repeat(40) { nested = allOf(nested) }

    assertThat(AppFunctionTypes.flattenAllOf(nested, components(), budget())).isNull()
  }

  @Test
  fun isPendingIntent_pendingIntentReturn_isTrue() {
    assertThat(AppFunctionTypes.isPendingIntent(pendingIntent(), components())).isTrue()
  }

  @Test
  fun isPendingIntent_nullablePendingIntentReturn_isTrue() {
    assertThat(AppFunctionTypes.isPendingIntent(pendingIntent(isNullable = true), components()))
      .isTrue()
  }

  @Test
  fun isPendingIntent_referenceToAPendingIntent_isTrue() {
    val components = components("Screen" to pendingIntent())

    assertThat(AppFunctionTypes.isPendingIntent(reference("Screen"), components)).isTrue()
  }

  @Test
  fun isPendingIntent_someOtherParcelable_isFalse() {
    val other = AppFunctionParcelableTypeMetadata(qualifiedName = "com.example.Doc", false)

    assertThat(AppFunctionTypes.isPendingIntent(other, components())).isFalse()
  }

  @Test
  fun isPendingIntent_valueThatIsNotAParcelable_isFalse() {
    assertThat(AppFunctionTypes.isPendingIntent(note(), components())).isFalse()
  }

  @Test
  fun carriesUndeliverablePendingIntent_barePendingIntent_isFalse() {
    // The one screen shape the toolset can hand to the app, so it is not undeliverable.
    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(pendingIntent(), components()))
      .isFalse()
  }

  @Test
  fun carriesUndeliverablePendingIntent_nullablePendingIntent_isFalse() {
    val type = pendingIntent(isNullable = true)

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isFalse()
  }

  @Test
  fun carriesUndeliverablePendingIntent_arrayOfPendingIntents_isTrue() {
    val type = array(pendingIntent())

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingOne_isTrue() {
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string(), "screen" to pendingIntent()),
        required = listOf("title"),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingAMergeableCompositionWithoutAScreen_isFalse() {
    // The composition merges and holds no screen, so the function is deliverable. Without this the
    // all-of branch could answer "undeliverable" unconditionally and every other test would agree.
    val composed = allOf(note())
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("merged" to composed),
        required = emptyList(),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isFalse()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingAMergeableCompositionWithAScreen_isTrue() {
    // The composition merges, so the screen is found inside it rather than assumed by fallback --
    // the branch the unmergeable case below never reaches.
    val composed =
      allOf(
        AppFunctionObjectTypeMetadata(
          properties = mapOf("screen" to pendingIntent()),
          required = emptyList(),
          qualifiedName = "com.example.Screened",
          isNullable = false,
        )
      )
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("merged" to composed),
        required = emptyList(),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingNestedArraysOfThem_isTrue() {
    // Two levels of array, so the recursion has to carry past the first.
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("screens" to array(array(pendingIntent()))),
        required = emptyList(),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingAnUnmergeableComposition_isTrue() {
    // A composition that will not merge cannot be inspected, so it is refused rather than assumed
    // deliverable -- the one place in this file where not knowing counts against the function.
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("merged" to allOf(reference("Missing"))),
        required = emptyList(),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingADifferentParcelable_isFalse() {
    // Only a PendingIntent is a screen. Any other parcelable is simply unrepresentable, and the
    // schema converter has already dropped it, so the function is still worth offering.
    val type =
      AppFunctionObjectTypeMetadata(
        properties =
          mapOf(
            "title" to string(),
            "attachment" to
              AppFunctionParcelableTypeMetadata(
                qualifiedName = "android.net.Uri",
                isNullable = false,
              ),
          ),
        required = listOf("title"),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isFalse()
  }

  @Test
  fun carriesUndeliverablePendingIntent_objectHoldingAnArrayOfThem_isTrue() {
    val type =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("screens" to array(pendingIntent())),
        required = listOf(),
        qualifiedName = "com.example.Result",
        isNullable = false,
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_allOfMemberHoldingOne_isTrue() {
    val screen =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("screen" to pendingIntent()),
        required = listOf("screen"),
        qualifiedName = "com.example.Screen",
        isNullable = false,
      )

    val type = allOf(note(), screen)

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isTrue()
  }

  @Test
  fun carriesUndeliverablePendingIntent_valueWithNoScreenInIt_isFalse() {
    val type = array(note())

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(type, components())).isFalse()
  }

  @Test(timeout = 30_000)
  fun carriesUndeliverablePendingIntent_metadataDeeperThanTheCap_saysUndeliverable() {
    // Fails closed: metadata too deep to check is treated as carrying a screen rather than waved
    // through, which is the direction every other cap in this file takes.
    var nested: AppFunctionDataTypeMetadata = string()
    repeat(40) {
      nested =
        AppFunctionObjectTypeMetadata(
          properties = mapOf("next" to nested),
          required = listOf(),
          qualifiedName = "com.example.Nested",
          isNullable = false,
        )
    }

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(nested, components())).isTrue()
  }

  @Test(timeout = 30_000)
  fun carriesUndeliverablePendingIntent_selfReferentialMetadata_terminatesAndFailsClosed() {
    // Another app's metadata, so the walk is bounded rather than trusted to be acyclic. It runs
    // out of depth rather than proving anything, so it answers "undeliverable".
    val components =
      components(
        "A" to
          AppFunctionObjectTypeMetadata(
            properties = mapOf("next" to reference("A")),
            required = listOf(),
            qualifiedName = "com.example.A",
            isNullable = false,
          )
      )

    assertThat(AppFunctionTypes.carriesUndeliverablePendingIntent(reference("A"), components))
      .isTrue()
  }

  private companion object {
    /** A budget of its own per test, matching what the schema converter grants a conversion. */
    fun budget() = intArrayOf(1024)

    fun string() = AppFunctionStringTypeMetadata(isNullable = false)

    fun pendingIntent(isNullable: Boolean = false) =
      AppFunctionParcelableTypeMetadata(
        qualifiedName = "android.app.PendingIntent",
        isNullable = isNullable,
      )

    fun array(itemType: AppFunctionDataTypeMetadata) =
      AppFunctionArrayTypeMetadata(itemType = itemType, isNullable = false)

    fun reference(name: String) =
      AppFunctionReferenceTypeMetadata(referenceDataType = name, isNullable = false)

    fun note() =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to AppFunctionStringTypeMetadata(isNullable = false)),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    fun allOf(vararg members: AppFunctionDataTypeMetadata) =
      AppFunctionAllOfTypeMetadata(
        matchAll = members.toList(),
        qualifiedName = "com.example.Merged",
        isNullable = false,
      )

    fun components(vararg types: Pair<String, AppFunctionDataTypeMetadata>) =
      AppFunctionComponentsMetadata(dataTypes = types.toMap())
  }
}
