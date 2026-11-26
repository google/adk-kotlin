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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.interop

import com.fasterxml.jackson.core.type.TypeReference
import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.interop.JavaPortTypes.Box
import com.google.adk.kt.interop.JavaPortTypes.Catchall
import com.google.adk.kt.interop.JavaPortTypes.Chain
import com.google.adk.kt.interop.JavaPortTypes.Contact
import com.google.adk.kt.interop.JavaPortTypes.Coords
import com.google.adk.kt.interop.JavaPortTypes.Counted
import com.google.adk.kt.interop.JavaPortTypes.Customer
import com.google.adk.kt.interop.JavaPortTypes.Defaults
import com.google.adk.kt.interop.JavaPortTypes.Entry
import com.google.adk.kt.interop.JavaPortTypes.Hidden
import com.google.adk.kt.interop.JavaPortTypes.Hinted
import com.google.adk.kt.interop.JavaPortTypes.Holder
import com.google.adk.kt.interop.JavaPortTypes.Invoice
import com.google.adk.kt.interop.JavaPortTypes.InvoiceLine
import com.google.adk.kt.interop.JavaPortTypes.Irregular
import com.google.adk.kt.interop.JavaPortTypes.Keywords
import com.google.adk.kt.interop.JavaPortTypes.Label
import com.google.adk.kt.interop.JavaPortTypes.Labels
import com.google.adk.kt.interop.JavaPortTypes.Lenient
import com.google.adk.kt.interop.JavaPortTypes.Level
import com.google.adk.kt.interop.JavaPortTypes.Login
import com.google.adk.kt.interop.JavaPortTypes.Memo
import com.google.adk.kt.interop.JavaPortTypes.Money
import com.google.adk.kt.interop.JavaPortTypes.Note
import com.google.adk.kt.interop.JavaPortTypes.NoteView
import com.google.adk.kt.interop.JavaPortTypes.NoteViewImpl
import com.google.adk.kt.interop.JavaPortTypes.NullableDraft
import com.google.adk.kt.interop.JavaPortTypes.Order
import com.google.adk.kt.interop.JavaPortTypes.Packed
import com.google.adk.kt.interop.JavaPortTypes.Parsed
import com.google.adk.kt.interop.JavaPortTypes.Pet
import com.google.adk.kt.interop.JavaPortTypes.Plain
import com.google.adk.kt.interop.JavaPortTypes.Point
import com.google.adk.kt.interop.JavaPortTypes.Priced
import com.google.adk.kt.interop.JavaPortTypes.Profile
import com.google.adk.kt.interop.JavaPortTypes.Quote
import com.google.adk.kt.interop.JavaPortTypes.Ref
import com.google.adk.kt.interop.JavaPortTypes.Scalars
import com.google.adk.kt.interop.JavaPortTypes.Serial
import com.google.adk.kt.interop.JavaPortTypes.Shipment
import com.google.adk.kt.interop.JavaPortTypes.Signup
import com.google.adk.kt.interop.JavaPortTypes.Slip
import com.google.adk.kt.interop.JavaPortTypes.Sparse
import com.google.adk.kt.interop.JavaPortTypes.Strict
import com.google.adk.kt.interop.JavaPortTypes.Tagged
import com.google.adk.kt.interop.JavaPortTypes.Tags
import com.google.adk.kt.interop.JavaPortTypes.Tally
import com.google.adk.kt.interop.JavaPortTypes.Task
import com.google.adk.kt.interop.JavaPortTypes.Tree
import com.google.adk.kt.interop.JavaPortTypes.Words
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.GraphValidationException
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeBody
import com.google.adk.kt.workflow.RequestInput
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Checks the schemas [inferJavaPortSchema] infers from Java port types, and that edges between Java
 * and Kotlin ports build and run as those schemas say.
 */
class JavaPortSchemasTest {

  @Serializable
  data class Ticket(val id: String, val priority: Int, val score: Double, val tags: List<String>)

  @Serializable data class Draft(val note: String?)

  @Test
  fun javaRecordAndSerializableTwin_inferTheSameSchemas() {
    // Arrange
    val java = JavaPortTypes.Ticket::class.java
    val kotlinNode = node<Ticket, Ticket>("kotlin") { _, ticket -> ticket }

    // Act
    val output = inferred(java, forOutput = true)
    val input = inferred(java, forOutput = false)

    // Assert
    assertEquals(kotlinNode.outputSchema, output)
    assertEquals(kotlinNode.inputSchema, input)
  }

  @Test
  fun nullableField_isOptionalOnInputAndAnObjectFieldTakesAnyValue() {
    // Act
    val input = inferred(Order::class.java, forOutput = false)
    val output = inferred(Order::class.java, forOutput = true)

    // Assert
    val properties = mapOf("id" to STRING, "note" to NULLABLE_STRING, "extra" to Schema())
    assertEquals(closed(properties, required = listOf("id", "extra")), input)
    assertEquals(closed(properties, required = listOf("id", "note", "extra")), output)
  }

  @Test
  fun profile_inputRequiresNonNullablePropertiesAndOutputRequiresAlwaysWrittenOnes() {
    // Arrange
    val profile = Profile("Ada", /* nickname= */ null, AtomicReference(), /* visits= */ 0)

    // Act
    val input = inferred(Profile::class.java, forOutput = false)
    val output = inferred(Profile::class.java, forOutput = true)
    val written = MAPPER.convertValue(profile, Map::class.java).keys

    // Assert
    // The alias is one more optional input key; NON_NULL omits a null nickname from the output.
    assertEquals(
      closed(
        mapOf(
          "name" to STRING,
          "nickname" to NULLABLE_STRING,
          "nick" to NULLABLE_STRING,
          "email" to NULLABLE_STRING,
          "visits" to INTEGER,
        ),
        required = listOf("name", "visits"),
      ),
      input,
    )
    assertEquals(
      closed(
        mapOf(
          "name" to STRING,
          "nickname" to STRING,
          "email" to NULLABLE_STRING,
          "visits" to INTEGER,
        ),
        required = listOf("name", "email", "visits"),
      ),
      output,
    )
    assertEquals(setOf("name", "email", "visits"), written)
  }

  @Test
  fun enumsAndScalars_inferTheirJsonTypes() {
    // Arrange
    val task = closed(mapOf("priority" to PRIORITY), listOf("priority"))
    val scalars =
      mapOf(
        "bytes" to STRING,
        "chars" to STRING,
        "letter" to STRING,
        "flag" to Schema(type = Type.BOOLEAN),
        "counts" to Schema(type = Type.OBJECT),
      )

    // Act
    val taskIn = inferred(Task::class.java, forOutput = false)
    val taskOut = inferred(Task::class.java, forOutput = true)
    val scalarsIn = inferred(Scalars::class.java, forOutput = false)
    val scalarsOut = inferred(Scalars::class.java, forOutput = true)
    val levelIn = inferred(Level::class.java, forOutput = false)
    val levelOut = inferred(Level::class.java, forOutput = true)

    // Assert: byte[], char[], and char write as text; maps as objects; numeric enums infer null.
    assertEquals(task, taskIn)
    assertEquals(task, taskOut)
    assertEquals(closed(scalars), scalarsIn)
    assertEquals(closed(scalars, scalars.keys.toList()), scalarsOut)
    assertNull(levelIn)
    assertNull(levelOut)
  }

  @Test
  fun abstractFactoryType_infersInputSchemaAndNoOutputSchema() {
    // Act
    val input = inferred(Point::class.java, forOutput = false)
    val output = inferred(Point::class.java, forOutput = true)

    // Assert: an abstract output writes as its runtime subclass, so it has no output schema.
    assertEquals(closed(mapOf("x" to INTEGER), listOf("x")), input)
    assertNull(output)
  }

  @Test
  fun typesWithoutFixedObjectKeys_inferNoSchema() {
    // Arrange: unwrapped, array shape, any-getter/setter, object id, type id, or cyclic types.
    val types =
      listOf(
        Contact::class.java,
        Coords::class.java,
        Tagged::class.java,
        Catchall::class.java,
        Ref::class.java,
        Pet::class.java,
        Shipment::class.java,
        Tree::class.java,
      )

    // Act
    val inferred = types.associateWith {
      listOf(inferred(it, forOutput = false), inferred(it, forOutput = true))
    }

    // Assert
    assertEquals(types.associateWith { listOf<Schema?>(null, null) }, inferred)
  }

  @Test
  fun writeOnlyUnwrappedAndExternalTypeId_inferNoSchema() {
    // Act
    val unwrappedIn = inferred(JavaPortTypes.WriteOnlyUnwrapped::class.java, forOutput = false)
    val unwrappedOut = inferred(JavaPortTypes.WriteOnlyUnwrapped::class.java, forOutput = true)
    val externalIn = inferred(JavaPortTypes.WriteOnlyExternalType::class.java, forOutput = false)
    val externalOut = inferred(JavaPortTypes.WriteOnlyExternalType::class.java, forOutput = true)

    // Assert: readView catches WRITE_ONLY @JsonUnwrapped and EXTERNAL_PROPERTY fields.
    assertNull(unwrappedIn)
    assertNull(unwrappedOut)
    assertNull(externalIn)
    assertNull(externalOut)
  }

  @Test
  fun delegatingAndScalarCreators_shapeOnlyTheInputSide() {
    // Arrange
    val tags = Schema(type = Type.ARRAY, items = STRING)

    // Act
    val tagsIn = inferred(Tags::class.java, forOutput = false)
    val tagsOut = inferred(Tags::class.java, forOutput = true)
    val labelIn = inferred(Label::class.java, forOutput = false)
    val labelOut = inferred(Label::class.java, forOutput = true)
    val moneyIn = inferred(Money::class.java, forOutput = false)
    val moneyOut = inferred(Money::class.java, forOutput = true)

    // Assert: a sole delegating creator sets the input; a second scalar form leaves input null.
    assertEquals(Schema(type = Type.OBJECT), tagsIn)
    assertEquals(closed(mapOf("values" to tags), listOf("values")), tagsOut)
    assertNull(labelIn)
    assertEquals(closed(mapOf("text" to STRING), listOf("text")), labelOut)
    assertNull(moneyIn)
    assertEquals(closed(mapOf("amount" to Schema(type = Type.NUMBER)), listOf("amount")), moneyOut)
  }

  @Test
  fun nullableOnAnyAccessorOrDeclarationAnnotation_marksPropertyNullable() {
    // Arrange
    val hinted = mapOf("text" to STRING, "hint" to NULLABLE_STRING)

    // Act
    val noteIn = inferred(Note::class.java, forOutput = false)
    val noteOut = inferred(Note::class.java, forOutput = true)
    val memoIn = inferred(Memo::class.java, forOutput = false)
    val memoOut = inferred(Memo::class.java, forOutput = true)
    val hintedIn = inferred(Hinted::class.java, forOutput = false)
    val hintedOut = inferred(Hinted::class.java, forOutput = true)

    // Assert: a @Nullable on a field, getter, or declaration annotation marks the property.
    assertEquals(closed(mapOf("text" to NULLABLE_STRING)), noteIn)
    assertEquals(closed(mapOf("text" to NULLABLE_STRING), listOf("text")), noteOut)
    assertEquals(closed(mapOf("note" to NULLABLE_STRING)), memoIn)
    assertEquals(closed(mapOf("note" to NULLABLE_STRING), listOf("note")), memoOut)
    assertEquals(closed(hinted, listOf("text")), hintedIn)
    assertEquals(closed(hinted, listOf("text", "hint")), hintedOut)
  }

  @Test
  fun inputRequiredKeys_followCreatorsAliasesInclusionsAndWriteVisibility() {
    // Arrange
    val tags = Schema(type = Type.ARRAY, items = STRING)
    val strict = closed(mapOf("id" to NULLABLE_STRING), listOf("id"))
    val lenient = mapOf("count" to INTEGER, "unit" to closed(mapOf("id" to STRING), listOf("id")))

    // Act
    val strictIn = inferred(Strict::class.java, forOutput = false)
    val strictOut = inferred(Strict::class.java, forOutput = true)
    val plainIn = inferred(Plain::class.java, forOutput = false)
    val plainOut = inferred(Plain::class.java, forOutput = true)
    val labelsIn = inferred(Labels::class.java, forOutput = false)
    val labelsOut = inferred(Labels::class.java, forOutput = true)
    val lenientIn = inferred(Lenient::class.java, forOutput = false)
    val lenientOut = inferred(Lenient::class.java, forOutput = true)
    val hiddenIn = inferred(Hidden::class.java, forOutput = false)
    val hiddenOut = inferred(Hidden::class.java, forOutput = true)

    // Assert: required creator wins; non-creator, aliased, and included keys are optional.
    assertEquals(strict, strictIn)
    assertEquals(strict, strictOut)
    assertEquals(closed(mapOf("name" to STRING)), plainIn)
    assertEquals(closed(mapOf("name" to STRING), listOf("name")), plainOut)
    assertEquals(closed(mapOf("name" to STRING, "title" to STRING, "tags" to tags)), labelsIn)
    assertEquals(closed(mapOf("name" to STRING, "tags" to tags), listOf("name")), labelsOut)
    assertEquals(closed(lenient), lenientIn)
    assertEquals(closed(lenient, listOf("count", "unit")), lenientOut)
    // allowSetters hides b from writing, but an input still requires it: absent, it reads null.
    assertEquals(closed(mapOf("a" to STRING, "b" to STRING), listOf("a", "b")), hiddenIn)
    assertEquals(closed(mapOf("a" to STRING), listOf("a")), hiddenOut)
  }

  @Test
  fun derivedGetter_isOptionalOnInputAndRequiredOnOutput() {
    // Arrange
    val invoice = mapOf("id" to STRING, "quantity" to INTEGER, "total" to INTEGER)

    // Act
    val input = inferred(Invoice::class.java, forOutput = false)
    val output = inferred(Invoice::class.java, forOutput = true)

    // Assert: an input also takes, as optional, the marked total the type only writes.
    assertEquals(closed(invoice, listOf("id", "quantity")), input)
    assertEquals(closed(invoice, listOf("id", "quantity", "total")), output)
  }

  @Test
  fun delegatingCreator_givesItsDelegatesInputSchemaUnlessAnotherFormIsRead() {
    // Arrange
    val expected =
      mapOf(
        Entry::class.java to Schema(type = Type.OBJECT),
        // A BigInteger delegate is a scalar creator too, of the delegate's own form.
        Serial::class.java to Schema(type = Type.INTEGER),
        Chain::class.java to null,
        Holder::class.java to closed(mapOf("tags" to Schema(type = Type.OBJECT)), listOf("tags")),
        // A string or list creator reads a second form, and an own deserializer reads any.
        Keywords::class.java to null,
        Words::class.java to null,
        Parsed::class.java to null,
      )

    // Act
    val inferred = expected.keys.associateWith { inferred(it, forOutput = false) }

    // Assert
    assertEquals(expected, inferred)
  }

  @Test
  fun inclusionsThatMayOmitAKey_makeItOptional() {
    // Act
    val sparseInput = inferred(Sparse::class.java, forOutput = false)
    val sparseOutput = inferred(Sparse::class.java, forOutput = true)
    val countedInput = inferred(Counted::class.java, forOutput = false)
    val countedOutput = inferred(Counted::class.java, forOutput = true)
    val defaultsOutput = inferred(Defaults::class.java, forOutput = true)
    val packedOutput = inferred(Packed::class.java, forOutput = true)

    // Assert: an omitted key is never null, but NON_NULL still writes an empty reference as null.
    assertEquals(
      closed(
        mapOf(
          "text" to STRING,
          "count" to INTEGER,
          "model" to NULLABLE_STRING,
          "label" to NULLABLE_STRING,
          "code" to INTEGER,
          "plain" to STRING,
        ),
        required = listOf("code", "plain"),
      ),
      sparseInput,
    )
    assertEquals(
      closed(
        mapOf(
          "text" to STRING,
          "count" to INTEGER,
          "model" to STRING,
          "label" to NULLABLE_STRING,
          "code" to Schema(),
          "plain" to STRING,
        ),
        required = listOf("code", "plain"),
      ),
      sparseOutput,
    )
    assertEquals(
      closed(
        mapOf(
          "count" to INTEGER,
          "label" to STRING,
          "note" to NULLABLE_STRING,
          "tag" to NULLABLE_STRING,
        )
      ),
      countedInput,
    )
    assertEquals(
      closed(
        mapOf("count" to INTEGER, "label" to STRING, "note" to STRING, "tag" to NULLABLE_STRING),
        required = listOf("count", "label"),
      ),
      countedOutput,
    )
    // Class-level NON_DEFAULT may omit any POJO field, while a record still writes nested records.
    assertEquals(
      closed(mapOf("priority" to PRIORITY, "note" to STRING, "extra" to NULLABLE_STRING)),
      defaultsOutput,
    )
    val unit = closed(mapOf("id" to STRING), listOf("id"))
    assertEquals(closed(mapOf("count" to INTEGER, "unit" to unit), listOf("unit")), packedOutput)
  }

  @Test
  fun writeOnlyPropertyIsNotWritten_andReadOnlyOneIsOptionalOnInput() {
    // Act
    val input = inferred(Login::class.java, forOutput = false)
    val output = inferred(Login::class.java, forOutput = true)

    // Assert
    val inputKeys = mapOf("user" to STRING, "password" to STRING, "role" to STRING)
    assertEquals(closed(inputKeys, listOf("user", "password")), input)
    assertEquals(closed(mapOf("user" to STRING, "role" to STRING), listOf("user", "role")), output)
  }

  @Test
  fun propertiesOfNoFixedJsonType_takeAnyValue() {
    // Act
    val input = inferred(Irregular::class.java, forOutput = false)
    val output = inferred(Irregular::class.java, forOutput = true)

    // Assert: each codec shapes only its side; a list of typed elements is still a list.
    val keys =
      listOf(
        "id",
        "parsed",
        "codes",
        "notes",
        "read",
        "count",
        "typed",
        "typedTickets",
        "byId",
        "pets",
        "raw",
      )
    val list = Schema(type = Type.ARRAY)
    assertEquals(
      closed(
        mapOf(
          "id" to INTEGER,
          "parsed" to Schema(),
          "codes" to list.copy(items = INTEGER),
          "notes" to Schema(),
          "read" to Schema(),
          "count" to Schema(),
          "typed" to Schema(),
          "typedTickets" to list,
          "byId" to Schema(),
          "pets" to list,
          "raw" to Schema(),
        ),
        // Jackson wraps an object id reference in a property of its own, not a creator property.
        keys - "byId",
      ),
      input,
    )
    assertEquals(
      closed(
        mapOf(
          "id" to Schema(),
          "parsed" to STRING,
          "codes" to Schema(),
          "notes" to list.copy(items = STRING),
          "read" to INTEGER,
          "count" to Schema(),
          "typed" to Schema(),
          "typedTickets" to list,
          "byId" to Schema(),
          "pets" to list,
          "raw" to Schema(),
        ),
        keys,
      ),
      output,
    )
  }

  @Test
  fun creatorAnySetter_infersNoSchemaOnInputOrOutput() {
    // Act
    val input = inferred(JavaPortTypes.CreatorAnySetter::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.CreatorAnySetter::class.java, forOutput = true)

    // Assert: @JsonAnySetter on a creator parameter accepts unknown keys, like a method any-setter.
    assertNull(input)
    assertNull(output)
  }

  @Test
  fun staticFactoryWithPublicFields_requiresCreatorParametersOnInput() {
    // Act
    val input = inferred(JavaPortTypes.FactoryWithField::class.java, forOutput = false)

    // Assert: Jackson 2.18 also puts FieldProperty in properties(), but CreatorProperty must win.
    assertEquals(closed(mapOf("id" to STRING, "count" to INTEGER), listOf("id", "count")), input)
  }

  @Test
  fun enumWithCreatorOrCustomDeserializer_infersOutputOnlyAndNoInputSchema() {
    // Act
    val creatorIn = inferred(JavaPortTypes.IntCreatorEnum::class.java, forOutput = false)
    val creatorOut = inferred(JavaPortTypes.IntCreatorEnum::class.java, forOutput = true)
    val customIn = inferred(JavaPortTypes.CustomDeserEnum::class.java, forOutput = false)
    val customOut = inferred(JavaPortTypes.CustomDeserEnum::class.java, forOutput = true)

    // Assert: writing still emits text names, while reading uses the custom creator/deserializer.
    assertNull(creatorIn)
    assertEquals(Schema(type = Type.STRING, enum = listOf("ONE", "TWO")), creatorOut)
    assertNull(customIn)
    assertEquals(Schema(type = Type.STRING, enum = listOf("ALPHA", "BETA")), customOut)
  }

  @Test
  fun writeOnlyNullableField_isOptionalAndNullableOnInput() {
    // Act
    val input = inferred(JavaPortTypes.WriteOnlyNullable::class.java, forOutput = false)

    // Assert: readView catches @Nullable on a WRITE_ONLY field omitted from serialization.
    assertEquals(closed(mapOf("id" to STRING, "secret" to NULLABLE_STRING), listOf("id")), input)
  }

  @Test
  fun customClassSerializer_leavesCreatorKeysOptionalOnInputAndInfersNoOutput() {
    // Act
    val input = inferred(JavaPortTypes.CustomSerializedCreator::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.CustomSerializedCreator::class.java, forOutput = true)

    // Assert: a non-bean serializer hides written keys, so creator keys stay optional on input.
    assertEquals(closed(mapOf("id" to STRING)), input)
    assertNull(output)
  }

  @Test
  fun typeInfoOnReferenceProperty_leavesPropertyUnconstrained() {
    // Act
    val input = inferred(JavaPortTypes.TypedReference::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.TypedReference::class.java, forOutput = true)

    // Assert: @JsonTypeInfo on an AtomicReference leaves its property schema unconstrained.
    assertEquals(closed(mapOf("ticket" to Schema())), input)
    assertEquals(closed(mapOf("ticket" to Schema()), listOf("ticket")), output)
  }

  @Test
  fun nullableElementTypeUseAnnotations_markArrayItemsNullable() {
    // Act
    val input = inferred(JavaPortTypes.NullableElements::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.NullableElements::class.java, forOutput = true)

    // Assert: @Nullable on elements marks items nullable; @Nullable on the array marks the array.
    val listOfNullable = Schema(type = Type.ARRAY, items = NULLABLE_STRING)
    val properties =
      mapOf(
        "list" to listOfNullable,
        "array" to listOfNullable,
        "nullableArray" to Schema(type = Type.ARRAY, items = STRING, nullable = true),
        "grid" to Schema(type = Type.ARRAY, items = listOfNullable),
        "refList" to listOfNullable.copy(nullable = true),
        "refLists" to Schema(type = Type.ARRAY, items = listOfNullable.copy(nullable = true)),
      )
    assertEquals(closed(properties, listOf("list", "array", "grid", "refLists")), input)
    assertEquals(
      closed(properties, listOf("list", "array", "nullableArray", "grid", "refList", "refLists")),
      output,
    )
  }

  @Test
  fun contentConverters_leaveTheConvertedSideUnconstrained() {
    // Act
    val codesIn = inferred(JavaPortTypes.ConvertedCodes::class.java, forOutput = false)
    val codesOut = inferred(JavaPortTypes.ConvertedCodes::class.java, forOutput = true)
    val readsIn = inferred(JavaPortTypes.ConvertedReads::class.java, forOutput = false)
    val readsOut = inferred(JavaPortTypes.ConvertedReads::class.java, forOutput = true)

    // Assert: each contentConverter affects only its own direction.
    val intList = Schema(type = Type.ARRAY, items = INTEGER)
    assertEquals(closed(mapOf("codes" to intList), listOf("codes")), codesIn)
    assertEquals(closed(mapOf("codes" to Schema()), listOf("codes")), codesOut)
    assertEquals(closed(mapOf("nums" to Schema()), listOf("nums")), readsIn)
    assertEquals(closed(mapOf("nums" to intList), listOf("nums")), readsOut)
  }

  @Test
  fun propertySerializedAsASupertype_infersTheSupertypeOnOutputOnly() {
    // Act
    val input = inferred(JavaPortTypes.WrittenAsBase::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.WrittenAsBase::class.java, forOutput = true)

    // Assert: @JsonSerialize(as/contentAs) writes BaseItem's keys; reading still takes SubItem's.
    val sub = closed(mapOf("name" to STRING, "extra" to STRING))
    val base = closed(mapOf("name" to STRING), listOf("name"))
    val fields = listOf("item", "items")
    val subItems = Schema(type = Type.ARRAY, items = sub)
    assertEquals(closed(mapOf("item" to sub, "items" to subItems), fields), input)
    val baseItems = Schema(type = Type.ARRAY, items = base)
    assertEquals(closed(mapOf("item" to base, "items" to baseItems), fields), output)
  }

  @Test
  fun propertySerializedAsASubtype_keepsItsDeclaredTypeSoItFitsItsOwnInput() {
    // Act
    val input = inferred(JavaPortTypes.WrittenAsSub::class.java, forOutput = false)
    val output = inferred(JavaPortTypes.WrittenAsSub::class.java, forOutput = true)

    // Assert: both sides describe BaseItem, without the extra key SubItem writes.
    val baseIn = closed(mapOf("name" to STRING))
    val baseOut = closed(mapOf("name" to STRING), listOf("name"))
    val fields = listOf("item", "items")
    val itemsIn = Schema(type = Type.ARRAY, items = baseIn)
    assertEquals(closed(mapOf("item" to baseIn, "items" to itemsIn), fields), input)
    val itemsOut = Schema(type = Type.ARRAY, items = baseOut)
    assertEquals(closed(mapOf("item" to baseOut, "items" to itemsOut), fields), output)
  }

  @Test
  fun nullableOnlyOnCreatorOrSetterParameter_marksPropertyNullable() {
    // Act
    val paramIn = inferred(JavaPortTypes.ParamNullable::class.java, forOutput = false)
    val paramOut = inferred(JavaPortTypes.ParamNullable::class.java, forOutput = true)
    val setterIn = inferred(JavaPortTypes.SetterNullable::class.java, forOutput = false)
    val setterOut = inferred(JavaPortTypes.SetterNullable::class.java, forOutput = true)

    // Assert: type-use @Nullable on a creator or setter parameter alone marks the property.
    assertEquals(closed(mapOf("text" to NULLABLE_STRING)), paramIn)
    assertEquals(closed(mapOf("text" to NULLABLE_STRING), listOf("text")), paramOut)
    assertEquals(closed(mapOf("text" to NULLABLE_STRING)), setterIn)
    assertEquals(closed(mapOf("text" to NULLABLE_STRING), listOf("text")), setterOut)
  }

  @Test
  fun topLevelAndListElementReferences_inferNullableSchemas() {
    // Arrange
    val topRefType =
      MAPPER.typeFactory.constructType(object : TypeReference<AtomicReference<String>>() {})
    val listOfRefsType =
      MAPPER.typeFactory.constructType(object : TypeReference<List<AtomicReference<String>>>() {})

    // Act
    val topRefIn = inferJavaPortSchema(topRefType, forOutput = false)
    val topRefOut = inferJavaPortSchema(topRefType, forOutput = true)
    val listOfRefsIn = inferJavaPortSchema(listOfRefsType, forOutput = false)
    val listOfRefsOut = inferJavaPortSchema(listOfRefsType, forOutput = true)

    // Assert: AtomicReference infers nullable both at top level and as a list element.
    assertEquals(NULLABLE_STRING, topRefIn)
    assertEquals(NULLABLE_STRING, topRefOut)
    assertEquals(Schema(type = Type.ARRAY, items = NULLABLE_STRING), listOfRefsIn)
    assertEquals(Schema(type = Type.ARRAY, items = NULLABLE_STRING), listOfRefsOut)
  }

  @Test
  fun repeatedOrNestedGenericType_isNotACycle() {
    // Arrange
    val type = MAPPER.typeFactory.constructType(object : TypeReference<Box<Box<Int>>>() {})

    // Act
    val box = inferJavaPortSchema(type, forOutput = false)

    // Assert
    val sides = listOf("left", "right")
    val intBox = closed(mapOf("left" to INTEGER, "right" to INTEGER), sides)
    assertEquals(closed(mapOf("left" to intBox, "right" to intBox), sides), box)
  }

  @Test
  fun nonDefaultDate_isOptionalOnOutput() {
    // Act
    val output = inferred(JavaPortTypes.NonDefaultDate::class.java, forOutput = true)

    // Assert: NON_DEFAULT omits epoch-zero Date (BeanUtil.getDefaultValue is non-null).
    assertEquals(closed(mapOf("createdAt" to Schema(), "id" to STRING), listOf("id")), output)
  }

  @Test
  fun inputReadAsAnotherClass_infersNoInputSchema() {
    // Act
    val viewIn = inferred(JavaPortTypes.NullableImplView::class.java, forOutput = false)
    val builtIn = inferred(JavaPortTypes.BuiltProfile::class.java, forOutput = false)
    val builtOut = inferred(JavaPortTypes.BuiltProfile::class.java, forOutput = true)

    // Assert: Jackson reads these through @JsonDeserialize(as) or a builder; output is unaffected.
    assertNull(viewIn)
    assertNull(builtIn)
    assertEquals(closed(mapOf("name" to STRING), listOf("name")), builtOut)
  }

  @Test
  fun nullableKotlinField_failsIntoAJavaFieldWhenBuiltUnlessTheFieldIsNullable() {
    // Arrange
    val draft = node<String, Draft>("draft") { _, _ -> Draft(null) }
    val strict =
      FunctionNodes.of("read", JavaPortTypes.Draft::class.java, String::class.java) { _, d ->
        "note=${d.note()}"
      }
    val nullable =
      FunctionNodes.of("read", NullableDraft::class.java, String::class.java) { _, d ->
        "note=${d.note()}"
      }

    // Act
    val error = assertFailsWith<GraphValidationException> { chain(draft, strict) }
    val events = runWorkflow(chain(draft, nullable))

    // Assert
    assertContains(error.message.orEmpty(), "Schema mismatch on edge draft -> read")
    assertEquals("note=null", events.outputOf("read"))
  }

  @Test
  fun knownLimit_nullableKotlinOutputIntoAJavaIntegerPort_failsWhenBuilt() {
    // Arrange
    val length = node<String, Int?>("length") { _, text -> text.length }
    val next =
      FunctionNodes.of("next", Int::class.javaObjectType, Int::class.javaObjectType) { _, n ->
        n + 1
      }

    // Act
    val error = assertFailsWith<GraphValidationException> { chain(length, next) }

    // Assert
    assertContains(error.message.orEmpty(), "Schema mismatch on edge length -> next")
  }

  @Test
  fun javaRecordOutput_fitsItsKotlinTwinInput() {
    // Arrange
    val write =
      FunctionNodes.of("write", String::class.java, NullableDraft::class.java) { _, _ ->
        NullableDraft(null)
      }
    val read = node<Draft, String>("read") { _, draft -> "note=${draft.note}" }

    // Act
    val events = runWorkflow(chain(write, read))

    // Assert: the Java output always writes note, which the Kotlin input requires.
    assertEquals("note=null", events.outputOf("read"))
  }

  @Test
  fun typesWritingKeysTheyDoNotRead_fitTheirOwnInputs() {
    // Act: Invoice writes a marked total, Tally one allowGetters names, InvoiceLine a nested total.
    val billed =
      runWorkflow(
        sameTypeWorkflow(Invoice::class.java, Invoice("a1", 2)) { "${it.id()}=${it.total}" }
      )
    val counted =
      runWorkflow(
        sameTypeWorkflow(Tally::class.java, Tally("t1", 3, 30)) { "${it.id()}=${it.total()}" }
      )
    val labeled =
      runWorkflow(
        sameTypeWorkflow(InvoiceLine::class.java, InvoiceLine("l1", Invoice("a1", 2))) {
          it.label()
        }
      )

    // Assert: Tally does not read its total back.
    assertEquals("a1=20", billed.outputOf("read"))
    assertEquals("t1=0", counted.outputOf("read"))
    assertEquals("l1", labeled.outputOf("read"))
  }

  @Test
  fun keyTheTargetOnlyWritesWithAnotherType_failsWhenBuilt() {
    // Arrange: Invoice writes total as an integer it does not read, and Quote writes it as text.
    val quote =
      FunctionNodes.of("quote", String::class.java, Quote::class.java) { _, _ ->
        Quote("a1", 2, "x")
      }
    val invoice =
      FunctionNodes.of("invoice", Invoice::class.java, String::class.java) { _, i -> i.id() }

    // Act
    val error = assertFailsWith<GraphValidationException> { chain(quote, invoice) }

    // Assert
    assertContains(error.message.orEmpty(), "Schema mismatch on edge quote -> invoice")
  }

  @Test
  fun recordReadingACreatorKeyItNeverWrites_failsItsOwnEdgeWhenBuilt() {
    // Act: Hidden (allowSetters) and Login (WRITE_ONLY) read keys they never write.
    val hidden =
      assertFailsWith<GraphValidationException> {
        sameTypeWorkflow(Hidden::class.java, Hidden("x", "y")) { it.a() }
      }
    val login =
      assertFailsWith<GraphValidationException> {
        sameTypeWorkflow(Login::class.java, Login("ann", "pw", "admin")) { it.user() }
      }

    // Assert
    assertContains(hidden.message.orEmpty(), "does not fit input schema")
    assertContains(login.message.orEmpty(), "does not fit input schema")
  }

  @Test
  fun derivedGetter_fitsItsOwnTypesInput() {
    // Act: Priced writes total, which it does not read back.
    val events =
      runWorkflow(
        sameTypeWorkflow(Priced::class.java, Priced("a1", 2, 1.5)) { "${it.id()}=${it.total}" }
      )

    // Assert
    assertEquals(
      mapOf("id" to "a1", "quantity" to 2L, "price" to 1.5, "total" to 3.0),
      events.outputOf("write"),
    )
    assertEquals("a1=3.0", events.outputOf("read"))
  }

  @Test
  fun knownLimit_getterAndSetterNamedApart_buildIntoTheSameTypeAndReadNull() {
    // Arrange: Slip writes label through its getter, but its setter reads lable.
    val slip = Slip().apply { setLable("memo") }

    // Act
    val events = runWorkflow(sameTypeWorkflow(Slip::class.java, slip) { "${it.label}" })

    // Assert: the edge builds, as a type fits its own input, and the reader gets null.
    assertEquals(mapOf("label" to "memo"), events.outputOf("write"))
    assertEquals("null", events.outputOf("read"))
  }

  @Test
  fun keyAnotherTypeDoesNotRead_failsWhenBuilt() {
    // Arrange
    val signup =
      FunctionNodes.of("signup", String::class.java, Signup::class.java) { _, _ -> Signup("a@b.c") }
    val customer =
      FunctionNodes.of("customer", Customer::class.java, String::class.java) { _, c ->
        "${c.email}"
      }

    // Act
    val error = assertFailsWith<GraphValidationException> { chain(signup, customer) }

    // Assert
    assertContains(error.message.orEmpty(), "Customer does not read [customerEmail]")
  }

  @Test
  fun inputReadAsAnotherClass_buildsWithoutAnEdgeCheckAndRuns() {
    // Arrange: Jackson reads NoteView as NoteViewImpl, whose note may be null.
    val write =
      FunctionNodes.of("write", String::class.java, NoteViewImpl::class.java) { _, _ ->
        NoteViewImpl("ada")
      }
    val read =
      FunctionNodes.of("read", NoteView::class.java, String::class.java) { _, v ->
        "${v.name}:${v.note}"
      }

    // Act
    val events = runWorkflow(chain(write, read))

    // Assert
    assertEquals("ada:null", events.outputOf("read"))
  }

  @Test
  fun resumedUseAsOutputReply_isParsedAsJsonForATypedPort() {
    // Act: each parent gives an agent's reply as its output, then pauses before it finishes.
    val number = resumedParentOutput(Int::class.javaObjectType, reply = "42", placeholder = 0)
    val draft = runCatching {
      resumedParentOutput(JavaPortTypes.Draft::class.java, "not json", JavaPortTypes.Draft("x"))
    }

    // Assert: as for a Kotlin port, the reply is parsed as JSON; text that is not JSON fails.
    assertEquals("Long:42", number)
    assertContains(draft.exceptionOrNull()?.message.orEmpty(), "value is not valid JSON")
  }

  /**
   * Runs a parent typed [type] that gives an agent's [reply] as its output, then waits on a task
   * agent, beside a node that asks the user; returns the parent's output as resumed on turn 2.
   */
  private fun <T : Any> resumedParentOutput(type: Class<T>, reply: String, placeholder: T): String =
    runBlocking {
      val writer = LlmAgent(name = "writer", model = ReplyModel(reply))
      val task = LlmAgent(name = "task", model = ReplyModel("Any detail?"), mode = AgentMode.TASK)
      val parent =
        FunctionNodes.builder(Content::class.java, type)
          .name("parent")
          .rerunOnResume(true)
          .body { ctx, _ ->
            val view = InteropContextView.of(ctx)
            view.runNodeBlocking(writer, null, RunNodeConfig.builder().useAsOutput(true).build())
            view.runNodeBlocking(task, null, RunNodeConfig.builder().raiseOnWait(true).build())
            placeholder
          }
          .build()
      val ask =
        FunctionNodes.of("ask", Content::class.java, String::class.java) { _, _ ->
          NodeBody.requestInput<String>("q", "proceed?")
        }
      val join = JoinNode(name = "join")
      val sink =
        FunctionNodes.of("sink", Any::class.java, String::class.java) { _, joined ->
          val output = (joined as Map<*, *>)["parent"]
          "${output?.javaClass?.simpleName}:$output"
        }
      val edges = listOf(Edge(Start, parent), Edge(Start, ask), Edge(parent, join), Edge(ask, join))
      val workflow = Workflow(name = "resume_wf", edges = edges + Edge(join, sink))
      val app =
        App(
          appName = "resume_wf",
          rootNode = workflow,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      val runner = InMemoryRunner(app)
      val asked =
        runner
          .runAsync(userId = "u", sessionId = "s", newMessage = Content.fromText(Role.USER, "go"))
          .toList()
      val answer =
        Content(
          role = Role.USER,
          parts =
            listOf(
              Part(
                functionResponse =
                  FunctionResponse(
                    id = "q",
                    name = RequestInput.FUNCTION_CALL_NAME,
                    response = mapOf("result" to "ok"),
                  )
              )
            ),
        )
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          invocationId = asked.first().invocationId,
          newMessage = answer,
        )
        .toList()
        .outputOf("sink") as String
    }

  /** A model that replies with [text] to every request. */
  private class ReplyModel(private val text: String) : Model {
    override val name = "reply"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      flowOf(LlmResponse(content = Content.fromText(Role.MODEL, text)))
  }

  /** Returns a workflow whose `write` outputs [value] as [type] and whose `read` reads it back. */
  private fun <T : Any> sameTypeWorkflow(type: Class<T>, value: T, read: (T) -> String): Workflow {
    val write = FunctionNodes.of("write", String::class.java, type) { _, _ -> value }
    val reader = FunctionNodes.of("read", type, String::class.java) { _, input -> read(input) }
    return chain(write, reader)
  }

  /** Returns a workflow that runs [first], then [second]. */
  private fun chain(first: Node, second: Node): Workflow =
    Workflow(name = "wf", edges = listOf(Edge(Start, first), Edge(first, second)))

  private fun List<Event>.outputOf(name: String): Any? =
    single { it.nodeInfo?.path?.endsWith("/$name@1") == true }.output

  /** Runs [workflow] once and returns its events. */
  private fun runWorkflow(workflow: Workflow): List<Event> = runBlocking {
    val runner = InMemoryRunner(App(appName = workflow.name, rootNode = workflow))
    runner
      .runAsync(userId = "u", sessionId = "s", newMessage = Content.fromText(Role.USER, "go"))
      .toList()
  }

  private fun inferred(type: Class<*>, forOutput: Boolean) =
    inferJavaPortSchema(MAPPER.typeFactory.constructType(type), forOutput)

  private fun closed(properties: Map<String, Schema>, required: List<String>? = null) =
    Schema(
      type = Type.OBJECT,
      properties = properties,
      required = required,
      additionalProperties = false,
    )

  companion object {
    private val STRING = Schema(type = Type.STRING)
    private val NULLABLE_STRING = STRING.copy(nullable = true)
    private val INTEGER = Schema(type = Type.INTEGER)
    private val PRIORITY = Schema(type = Type.STRING, enum = listOf("LOW", "urgent"))
  }
}
