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

package com.google.adk.kt.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Builds typed {@link FunctionNode}s from plain Java with {@link FunctionNodes} and runs them. */
@RunWith(JUnit4.class)
public final class FunctionNodeJavaInteropTest {

  record Order(String id, int quantity, double price) {}

  record Leave(int days, String reason) {}

  /** An interface port type, which has no single JSON shape. */
  interface Decision {}

  /** A polymorphic port type: Jackson writes a type id, so it has no single JSON shape. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes({@JsonSubTypes.Type(value = Circle.class, name = "circle")})
  interface Shape {}

  record Circle(double radius) implements Shape {}

  /** An Immutables-style value type: abstract, read through the concrete class it names. */
  @JsonDeserialize(as = SimpleRange.class)
  abstract static class Range {
    @JsonProperty("low")
    abstract int low();

    @JsonProperty("high")
    abstract int high();
  }

  static final class SimpleRange extends Range {
    private final int low;
    private final int high;

    @JsonCreator
    SimpleRange(@JsonProperty("low") int low, @JsonProperty("high") int high) {
      this.low = low;
      this.high = high;
    }

    @Override
    int low() {
      return low;
    }

    @Override
    int high() {
      return high;
    }
  }

  /** An AutoValue-style value type: abstract, but created through a Jackson factory. */
  abstract static class Point {
    @JsonProperty("x")
    abstract int x();

    @JsonCreator
    static Point create(@JsonProperty("x") int x) {
      return new AutoPoint(x);
    }
  }

  static final class AutoPoint extends Point {
    private final int x;

    AutoPoint(int x) {
      this.x = x;
    }

    @Override
    int x() {
      return x;
    }
  }

  record Approved(int days) implements Decision {}

  record Meeting(String title, Instant at, Duration length) {}

  /** A record whose {@code token} Jackson reads but never writes. */
  record Secret(String name, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token) {}

  record Rush(int qty, boolean rush, boolean urgent) {}

  record Ledger(long cents) {}

  enum Color {
    RED
  }

  /** A POJO whose {@code tier} field is written as {@code loyalty_tier}. */
  public static final class Customer {
    public String name;

    @JsonProperty("loyalty_tier")
    public String tier;
  }

  /** A class Jackson cannot write: it has no properties. */
  public static final class Opaque {}

  /** A class Jackson can create only from a string, not from the object it writes. */
  public static final class Code {
    private final String value;

    public Code(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  /** A value type: written as its string and read back through its delegating creator. */
  public static final class OrderId {
    private final String value;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public OrderId(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  /** A bean written as an object and read back through a delegating creator that takes a map. */
  public static final class Bag {
    private final Map<String, Object> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Bag(Map<String, Object> values) {
      this.values = values;
    }

    public Object getName() {
      return values.get("name");
    }
  }

  /** A class Jackson can read but not write: it has no getters. */
  public static final class Token {
    @SuppressWarnings("unused")
    private final String secret;

    @JsonCreator
    public Token(@JsonProperty("secret") String secret) {
      this.secret = secret;
    }
  }

  /** A class Jackson cannot create: its only constructor takes unannotated parameters. */
  public static final class NoCreator {
    public final String label;

    public NoCreator(String label, int unused) {
      this.label = label;
    }
  }

  @Test
  public void of_recordPorts_readTheJsonFormAndWriteTheSameFormAsKotlin() {
    // Arrange
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Order.class,
            (ctx, message) -> new Order(message.text(), 3, 2.5));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());

    // Act
    List<Event> events = run("records", "A-1", makeOrder, describe);

    // Assert
    // A Kotlin node writes whole numbers as Long and fractions as Double; equals tells them apart.
    assertEquals(
        ImmutableMap.of("id", "A-1", "quantity", 3L, "price", 2.5), outputOf(events, "make_order"));
    assertEquals("A-1 x3", outputOf(events, "describe"));
  }

  @Test
  public void of_pojoWithJsonProperty_usesTheJsonNameBothWays() {
    // Arrange
    FunctionNode<Content, Customer> makeCustomer =
        FunctionNodes.of(
            "make_customer",
            Content.class,
            Customer.class,
            (ctx, message) -> {
              Customer customer = new Customer();
              customer.name = message.text();
              customer.tier = "gold";
              return customer;
            });
    FunctionNode<Customer, String> greet =
        FunctionNodes.of(
            "greet", Customer.class, String.class, (ctx, c) -> c.name + " (" + c.tier + ")");

    // Act
    List<Event> events = run("pojo", "Ada", makeCustomer, greet);

    // Assert
    assertEquals(
        ImmutableMap.of("name", "Ada", "loyalty_tier", "gold"), outputOf(events, "make_customer"));
    assertEquals("Ada (gold)", outputOf(events, "greet"));
  }

  @Test
  public void of_stringPort_readsTheUsersMessageAsText() {
    // Arrange
    FunctionNode<String, String> shout =
        FunctionNodes.of("shout", String.class, String.class, (ctx, text) -> text + "!");

    // Act
    List<Event> events = run("string_port", "hi", shout);

    // Assert
    assertEquals("hi!", outputOf(events, "shout"));
    assertEquals(Type.STRING, shout.getInputSchema().getType());
    assertEquals(Boolean.TRUE, shout.getInputSchema().getNullable());
    assertEquals(Type.STRING, shout.getOutputSchema().getType());
  }

  @Test
  public void of_stringOutput_fitsANonNullStringInput() {
    // Arrange
    FunctionNode<String, String> shout =
        FunctionNodes.of("shout", String.class, String.class, (ctx, text) -> text + "!");
    FunctionNode<String, String> echo =
        FunctionNodes.builder(String.class, String.class)
            .name("echo")
            .inputSchema(Schema.builder().type(Type.STRING).build())
            .body((ctx, text) -> text)
            .build();

    // Act
    List<Event> events = run("string_output", "hi", shout, echo);

    // Assert
    assertEquals("hi!", outputOf(events, "echo"));
  }

  @Test
  public void of_stringPort_rejectsNonTextValues() {
    // Arrange
    Supplier<FunctionNode<String, String>> shout =
        () -> FunctionNodes.of("shout", String.class, String.class, (ctx, text) -> text + "!");
    FunctionNode<Object, Integer> number =
        FunctionNodes.of("number", Object.class, Integer.class, (ctx, in) -> 3);
    FunctionNode<Object, Boolean> flag =
        FunctionNodes.of("flag", Object.class, Boolean.class, (ctx, in) -> true);
    FunctionNode<Object, Map<String, Object>> record =
        FunctionNodes.of(
            "record",
            Object.class,
            new TypeReference<Map<String, Object>>() {},
            (ctx, in) -> ImmutableMap.of("text", "hi"));

    // Act
    boolean numberRejected = rejected(() -> run("string_number", "go", number, shout.get()));
    boolean booleanRejected = rejected(() -> run("string_boolean", "go", flag, shout.get()));
    boolean mapRejected = rejected(() -> run("string_map", "go", record, shout.get()));

    // Assert
    assertTrue(numberRejected);
    assertTrue(booleanRejected);
    assertTrue(mapRejected);
  }

  @Test
  public void of_stringPort_passesNullThrough() {
    // Arrange
    FunctionNode<Object, Object> nothing =
        FunctionNodes.of("nothing", Object.class, Object.class, (ctx, in) -> null);
    FunctionNode<String, String> describe =
        FunctionNodes.of(
            "describe", String.class, String.class, (ctx, text) -> text == null ? "no text" : text);

    // Act
    List<Event> events = run("string_null", "go", nothing, describe);

    // Assert
    assertEquals("no text", outputOf(events, "describe"));
  }

  @Test
  public void of_typeReferencePorts_fanOutAndCollectAListOfRecords() {
    // Arrange
    FunctionNode<Content, List<Integer>> readQuantities =
        FunctionNodes.of(
            "read_quantities",
            Content.class,
            new TypeReference<List<Integer>>() {},
            (ctx, message) -> ImmutableList.of(1, 2, 3));
    FunctionNode<Integer, Order> price =
        FunctionNodes.of(
            "price", Integer.class, Order.class, (ctx, n) -> new Order("o" + n, n, 1.5));
    FunctionNode<List<Order>, Double> total =
        FunctionNodes.of(
            "total",
            new TypeReference<List<Order>>() {},
            Double.class,
            (ctx, orders) -> orders.stream().mapToDouble(o -> o.quantity() * o.price()).sum());

    // Act
    List<Event> events =
        run("fan_out", "go", readQuantities, FunctionNode.parallelOf(price, 2), total);

    // Assert
    assertEquals(9.0, outputOf(events, "total"));
  }

  @Test
  public void parallelOf_typedItems_runsTheBodyOnEachItemInInputOrder() {
    // Arrange
    FunctionNode<Content, List<Integer>> readQuantities =
        FunctionNodes.of(
            "read_quantities",
            Content.class,
            new TypeReference<List<Integer>>() {},
            (ctx, message) -> ImmutableList.of(3, 1, 2));
    FunctionNode<List<Integer>, List<Order>> price =
        FunctionNode.parallelOf(
            FunctionNodes.of(
                "price", Integer.class, Order.class, (ctx, n) -> new Order("o" + n, n, 1.5)),
            2);
    FunctionNode<List<Order>, List<String>> label =
        FunctionNode.parallelOf(
            FunctionNodes.of("label", Order.class, String.class, (ctx, order) -> order.id()));
    FunctionNode<List<String>, String> join =
        FunctionNodes.of(
            "join",
            new TypeReference<List<String>>() {},
            String.class,
            (ctx, ids) -> String.join(",", ids));

    // Act
    List<Event> events = run("parallel_of", "go", readQuantities, price, label, join);
    String error =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNode.parallelOf(
                        FunctionNodes.of("bad", Integer.class, Integer.class, (c, n) -> n), 0))
            .getMessage();

    // Assert
    assertEquals("o3,o1,o2", outputOf(events, "join"));
    assertTrue(error, error.contains("maxParallelWorkers"));
  }

  @Test
  public void recordWithJavaTime_travelsAsIsoStrings() {
    // Arrange
    Instant noon = Instant.parse("2026-10-16T12:00:00Z");
    FunctionNode<Content, Meeting> plan =
        FunctionNodes.of(
            "plan",
            Content.class,
            Meeting.class,
            (ctx, m) -> new Meeting("sync", noon, Duration.ofMinutes(30)));
    FunctionNode<Meeting, String> describe =
        FunctionNodes.of(
            "describe",
            Meeting.class,
            String.class,
            (ctx, m) -> m.title() + " at " + m.at() + " for " + m.length());

    // Act
    List<Event> events = run("java_time", "go", plan, describe);

    // Assert
    assertEquals(
        ImmutableMap.of("title", "sync", "at", "2026-10-16T12:00:00Z", "length", "PT30M"),
        outputOf(events, "plan"));
    assertEquals("sync at 2026-10-16T12:00:00Z for PT30M", outputOf(events, "describe"));
  }

  @Test
  public void containerPorts_readEachItemOnce() {
    // Arrange
    FunctionNode<Object, Object> feed =
        FunctionNodes.of(
            "feed",
            Object.class,
            Object.class,
            (ctx, in) -> ImmutableList.of(ImmutableMap.of("name", "a", "token", "t-1")));
    FunctionNode<List<Secret>, String> read =
        FunctionNodes.builder(new TypeReference<List<Secret>>() {}, String.class)
            .name("read")
            .body((ctx, secrets) -> secrets.get(0).token())
            .build();
    FunctionNode<Secret[], String> readArray =
        FunctionNodes.of(
            "read", Secret[].class, String.class, (ctx, secrets) -> secrets[0].token());
    FunctionNode<Object, Object> feedMap =
        FunctionNodes.of(
            "feed",
            Object.class,
            Object.class,
            (ctx, in) -> ImmutableMap.of("a", ImmutableMap.of("name", "a", "token", "t-2")));
    FunctionNode<Map<String, Secret>, String> readMap =
        FunctionNodes.of(
            "read",
            new TypeReference<Map<String, Secret>>() {},
            String.class,
            (ctx, secrets) -> secrets.get("a").token());

    // Act
    List<Event> listEvents = run("list_once", "go", feed, read);
    List<Event> arrayEvents = run("array_once", "go", feed, readArray);
    List<Event> mapEvents = run("map_once", "go", feedMap, readMap);

    // Assert
    // A second Jackson pass would write each Secret and drop its write-only token.
    assertEquals("t-1", outputOf(listEvents, "read"));
    assertEquals("t-1", outputOf(arrayEvents, "read"));
    assertEquals("t-2", outputOf(mapEvents, "read"));
  }

  @Test
  public void nodeBody_checkedException_reachesTheCaller() {
    // Arrange
    FunctionNode<Content, String> load =
        FunctionNodes.of(
            "load",
            Content.class,
            String.class,
            (ctx, m) -> {
              throw new IOException("disk full");
            });

    // Act
    IOException error = assertThrows(IOException.class, () -> run("checked", "go", load));

    // Assert
    // The checked exception reaches the caller as it is, as a Kotlin body's exception does.
    assertEquals("disk full", error.getMessage());
  }

  @Test
  public void primitiveArrayPorts_roundTripAsJsonLists() {
    // Arrange
    FunctionNode<Content, double[]> prices =
        FunctionNodes.of(
            "prices", Content.class, double[].class, (ctx, m) -> new double[] {1.5, 2.0});
    FunctionNode<double[], Double> sum =
        FunctionNodes.of(
            "sum", double[].class, Double.class, (ctx, values) -> values[0] + values[1]);

    // Act
    List<Event> events = run("primitive_array", "go", prices, sum);

    // Assert
    assertEquals(ImmutableList.of(1.5, 2.0), outputOf(events, "prices"));
    assertEquals(3.5, outputOf(events, "sum"));
  }

  @Test
  public void valueAndDelegatingTypes_roundTripThroughPorts() {
    // Arrange
    FunctionNode<Content, OrderId> makeId =
        FunctionNodes.of("make_id", Content.class, OrderId.class, (ctx, m) -> new OrderId("A-1"));
    FunctionNode<OrderId, String> readId =
        FunctionNodes.of("read_id", OrderId.class, String.class, (ctx, id) -> "id " + id.value());
    FunctionNode<Content, Bag> makeBag =
        FunctionNodes.of(
            "make_bag",
            Content.class,
            Bag.class,
            (ctx, m) -> new Bag(ImmutableMap.of("name", "x")));
    FunctionNode<Bag, String> readBag =
        FunctionNodes.of("read_bag", Bag.class, String.class, (ctx, bag) -> "bag " + bag.getName());

    // Act
    List<Event> idEvents = run("value_type", "go", makeId, readId);
    List<Event> bagEvents = run("delegating_type", "go", makeBag, readBag);

    // Assert
    assertEquals("A-1", outputOf(idEvents, "make_id"));
    assertEquals("id A-1", outputOf(idEvents, "read_id"));
    assertEquals(ImmutableMap.of("name", "x"), outputOf(bagEvents, "make_bag"));
    assertEquals("bag x", outputOf(bagEvents, "read_bag"));
  }

  @Test
  public void abstractTypeReadAsItsDeclaredClass_roundTrips() {
    // Arrange
    FunctionNode<Content, Range> pick =
        FunctionNodes.of("pick", Content.class, Range.class, (ctx, m) -> new SimpleRange(2, 5));
    FunctionNode<Range, Integer> width =
        FunctionNodes.of("width", Range.class, Integer.class, (ctx, r) -> r.high() - r.low());

    // Act
    List<Event> events = run("as_class", "go", pick, width);

    // Assert
    assertEquals(ImmutableMap.of("low", 2L, "high", 5L), outputOf(events, "pick"));
    assertEquals(3L, outputOf(events, "width"));
  }

  @Test
  public void abstractValueTypeWithJacksonCreator_roundTrips() {
    // Arrange
    FunctionNode<Content, Point> place =
        FunctionNodes.of("place", Content.class, Point.class, (ctx, m) -> Point.create(3));
    FunctionNode<Point, Integer> readX =
        FunctionNodes.of("read_x", Point.class, Integer.class, (ctx, point) -> point.x());

    // Act
    List<Event> events = run("auto_value", "go", place, readX);

    // Assert
    assertEquals(ImmutableMap.of("x", 3L), outputOf(events, "place"));
    assertEquals(3L, outputOf(events, "read_x"));
  }

  @Test
  public void objectPort_passesASavedJsonFormThroughToATypedNode() {
    // Arrange
    ImmutableMap<String, Object> saved =
        ImmutableMap.of("id", "B-7", "quantity", 2L, "price", 4.0, "extra", "ignored");
    FunctionNode<Object, Object> replay =
        FunctionNodes.of("replay", Object.class, Object.class, (ctx, input) -> saved);
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());

    // Act
    List<Event> events = run("untyped", "go", replay, describe);

    // Assert
    assertEquals(saved, outputOf(events, "replay"));
    assertEquals("B-7 x2", outputOf(events, "describe"));
  }

  @Test
  public void of_portTypesJacksonCannotHandle_failWhenBuilt() {
    // Act
    String readError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of("bad_in", NoCreator.class, String.class, (ctx, n) -> n.label))
            .getMessage();
    String unsupportedError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of("bad_optional", Optional.class, String.class, (ctx, o) -> ""))
            .getMessage();
    String writeError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_out", String.class, Opaque.class, (ctx, s) -> new Opaque()))
            .getMessage();
    String elementError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_list",
                        new TypeReference<List<NoCreator>>() {},
                        String.class,
                        (ctx, list) -> ""))
            .getMessage();
    String keyError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_key",
                        new TypeReference<Map<Order, String>>() {},
                        String.class,
                        (ctx, map) -> ""))
            .getMessage();
    String builderError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.builder(NoCreator.class, String.class))
            .getMessage();
    String interfaceError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_decision", String.class, Decision.class, (ctx, s) -> new Approved(1)))
            .getMessage();
    String stringOnlyError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.of("bad_code", Code.class, String.class, (ctx, c) -> ""))
            .getMessage();
    String readOnlyError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.of("bad_token", Token.class, String.class, (ctx, t) -> ""))
            .getMessage();
    String polymorphicError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_shape", String.class, Shape.class, (ctx, s) -> new Circle(1.0)))
            .getMessage();
    String concretePolymorphicError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.of("bad_circle", Circle.class, String.class, (ctx, c) -> ""))
            .getMessage();
    String numberError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.of("bad_number", Number.class, String.class, (ctx, n) -> ""))
            .getMessage();
    String charSequenceError =
        assertThrows(
                WorkflowConfigurationException.class,
                () -> FunctionNodes.of("bad_text", String.class, CharSequence.class, (ctx, s) -> s))
            .getMessage();
    String abstractListError =
        assertThrows(
                WorkflowConfigurationException.class,
                () ->
                    FunctionNodes.of(
                        "bad_immutable",
                        new TypeReference<ImmutableList<String>>() {},
                        String.class,
                        (ctx, list) -> ""))
            .getMessage();

    // Assert
    assertTrue(
        readError,
        readError.startsWith(
            "The input of node 'bad_in' has type "
                + NoCreator.class.getName()
                + ", which Jackson cannot create from JSON"));
    assertTrue(
        unsupportedError,
        unsupportedError.startsWith(
            "The input of node 'bad_optional' has type java.util.Optional"));
    assertTrue(
        writeError,
        writeError.startsWith("The output of node 'bad_out' has type " + Opaque.class.getName()));
    assertTrue(elementError, elementError.startsWith("An element of the input of node 'bad_list'"));
    assertTrue(keyError, keyError.contains("cannot read as a map key"));
    assertTrue(
        builderError,
        builderError.startsWith("The input of a FunctionNodes.builder node has type"));
    assertTrue(interfaceError, interfaceError.contains("is an interface or abstract class"));
    assertTrue(
        interfaceError, interfaceError.contains("concrete record, wrap the hierarchy in one"));
    assertTrue(stringOnlyError, stringOnlyError.contains("only from a string or number"));
    assertTrue(
        readOnlyError,
        readOnlyError.startsWith(
            "The input of node 'bad_token' has type "
                + Token.class.getName()
                + ", which Jackson cannot write as JSON"));
    assertTrue(polymorphicError, polymorphicError.contains("is polymorphic (@JsonTypeInfo)"));
    assertTrue(
        concretePolymorphicError,
        concretePolymorphicError.contains("is polymorphic (@JsonTypeInfo)"));
    assertTrue(numberError, numberError.contains("without a Jackson creator"));
    assertTrue(numberError, numberError.contains("Long, Double or BigDecimal"));
    assertTrue(charSequenceError, charSequenceError.contains("Declare the port as String."));
    assertTrue(
        abstractListError,
        abstractListError.startsWith("The input of node 'bad_immutable' has type"));
  }

  @Test
  public void typedPort_messageOrUnconvertibleValue_isRejected() {
    // Arrange
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, o) -> o.id());
    FunctionNode<Object, Object> feedWord =
        FunctionNodes.of("feed", Object.class, Object.class, (ctx, in) -> "abc");
    FunctionNode<Integer, Integer> twice =
        FunctionNodes.of("twice", Integer.class, Integer.class, (ctx, n) -> n * 2);

    // Act
    boolean messageRejected = rejected(() -> run("message", "{\"id\": \"A-1\"}", describe));
    boolean wordRejected = rejected(() -> run("word", "go", feedWord, twice));

    // Assert
    assertTrue(messageRejected);
    assertTrue(wordRejected);
  }

  @Test
  public void builder_recordPorts_readBackAndKeepTheirCodecsThroughToBuilder() {
    // Arrange
    Schema object = Schema.builder().type(Type.OBJECT).build();
    Schema text = Schema.builder().type(Type.STRING).build();
    FunctionNode<Order, String> describe =
        FunctionNodes.builder(Order.class, String.class)
            .name("describe")
            .description("Describes an order.")
            .inputSchema(object)
            .outputSchema(text)
            .body((ctx, order) -> order.id() + " x" + order.quantity())
            .build();
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Order.class,
            (ctx, message) -> new Order(message.text(), 4, 1.0));

    // Act
    // The copy still reads its input as an Order; with an untyped port, order.id() would fail.
    FunctionNode<Order, String> copy = describe.toBuilder().name("describe_v2").build();
    List<Event> events = run("builder", "C-3", makeOrder, copy);

    // Assert
    assertEquals("Describes an order.", copy.getDescription());
    assertEquals(object, copy.getInputSchema());
    assertEquals(text, copy.getOutputSchema());
    assertNull(
        FunctionNodes.of("untyped", Object.class, Object.class, (c, i) -> i).getInputSchema());
    assertEquals("C-3 x4", outputOf(events, "describe_v2"));
  }

  @Test
  public void nullFieldsAndMapValues_areWrittenLikeAKotlinNodeWritesThem() {
    // Arrange
    Map<String, Object> withNull = new HashMap<>();
    withNull.put("note", null);
    FunctionNode<Content, Map<String, Object>> makeMap =
        FunctionNodes.of(
            "make_map",
            Content.class,
            new TypeReference<Map<String, Object>>() {},
            (ctx, message) -> withNull);
    FunctionNode<Map<String, Object>, Customer> makeCustomer =
        FunctionNodes.of(
            "make_customer",
            new TypeReference<Map<String, Object>>() {},
            Customer.class,
            (ctx, map) -> {
              Customer customer = new Customer();
              customer.name = String.valueOf(map.containsKey("note"));
              return customer;
            });

    // Act
    List<Event> events = run("nulls", "go", makeMap, makeCustomer);

    // Assert
    // A Kotlin node writes a null field as an explicit null, so the unset tier is a null entry.
    Map<String, Object> customerWithNullTier = new HashMap<>();
    customerWithNullTier.put("name", "true");
    customerWithNullTier.put("loyalty_tier", null);
    assertEquals(withNull, outputOf(events, "make_map"));
    assertEquals(customerWithNullTier, outputOf(events, "make_customer"));
  }

  @Test
  public void of_callsNodeBodyRequestInput_pausesAndPassesTheAnswerToItsSuccessor() {
    // Arrange: a body typed String asks on its only path.
    FunctionNode<Content, String> askName =
        FunctionNodes.of(
            "ask_name",
            Content.class,
            String.class,
            (ctx, content) -> NodeBody.requestInput("ask_name", "Name?"));
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);
    JavaAskSession session = new JavaAskSession(askName, greet, "ask_wf");

    // Act
    List<Event> asked = session.run(Content.fromText("user", "hi"));
    List<Event> answered = session.run(answer("ask_name", "Ada"));

    // Assert
    assertTrue(asked.stream().allMatch(e -> e.getOutput() == null));
    assertTrue(
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .anyMatch(
                c ->
                    c.getName().equals(RequestInput.FUNCTION_CALL_NAME)
                        && Objects.equals(c.getId(), "ask_name")
                        && Objects.equals(c.getArgs().get("message"), "Name?")));
    assertTrue(answered.stream().map(Event::getOutput).anyMatch("Hello, Ada"::equals));
  }

  @Test
  @SuppressWarnings("CatchingUnchecked") // The test shows what `catch (Exception e)` misses.
  public void of_requestInputInsideCatchException_stillAsks() {
    // Arrange: a catch-all for exceptions around the call does not swallow the request.
    FunctionNode<Content, String> askName =
        FunctionNodes.of(
            "ask_name",
            Content.class,
            String.class,
            (ctx, content) -> {
              try {
                return NodeBody.requestInput("ask_name", "Name?");
              } catch (Exception e) {
                return "swallowed";
              }
            });
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);

    // Act
    List<Event> asked =
        new JavaAskSession(askName, greet, "catch_wf").run(Content.fromText("user", "hi"));

    // Assert
    assertTrue(asked.stream().noneMatch(e -> Objects.equals(e.getOutput(), "swallowed")));
    assertTrue(asked.stream().anyMatch(e -> !e.functionCalls().isEmpty()));
  }

  @Test
  public void of_requestInputInsideCatchThrowable_doesNotAskAndOutputsTheFallback() {
    // Arrange: a catch-all for throwables around the call swallows the request.
    FunctionNode<Content, String> askName =
        FunctionNodes.of(
            "ask_name",
            Content.class,
            String.class,
            (ctx, content) -> {
              try {
                return NodeBody.requestInput("ask_name", "Name?");
              } catch (Throwable t) {
                return "fallback";
              }
            });
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);

    // Act
    List<Event> events =
        new JavaAskSession(askName, greet, "catch_throwable_wf")
            .run(Content.fromText("user", "hi"));

    // Assert: no request is sent, and the successor receives the fallback.
    assertTrue(events.stream().allMatch(e -> e.functionCalls().isEmpty()));
    assertTrue(events.stream().anyMatch(e -> Objects.equals(e.getOutput(), "Hello, fallback")));
  }

  @Test
  public void requestInput_inAnExpressionLambda_asksOnTheConditionalBranch() {
    // Arrange: the body asks only when the message is not a name.
    FunctionNode<Content, String> askName =
        FunctionNodes.of(
            "ask_name",
            Content.class,
            String.class,
            (ctx, content) ->
                content.text().startsWith("I am ")
                    ? content.text().substring(5)
                    : NodeBody.requestInput("ask_name", "Name?"));
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);

    // Act
    List<Event> named =
        new JavaAskSession(askName, greet, "named_wf").run(Content.fromText("user", "I am Ada"));
    List<Event> asked =
        new JavaAskSession(askName, greet, "unnamed_wf").run(Content.fromText("user", "hi"));

    // Assert
    assertTrue(named.stream().anyMatch(e -> Objects.equals(e.getOutput(), "Hello, Ada")));
    assertTrue(named.stream().allMatch(e -> e.functionCalls().isEmpty()));
    assertTrue(asked.stream().anyMatch(e -> !e.functionCalls().isEmpty()));
  }

  @Test
  public void requestInput_recordPayload_sendsItsJsonForm() {
    // Arrange
    FunctionNode<Content, String> ask =
        FunctionNodes.of(
            "ask",
            Content.class,
            String.class,
            (ctx, content) ->
                FunctionNodes.requestInput("ask_leave", "Approve?", new Leave(3, "trip")));
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);

    // Act
    List<Event> asked =
        new JavaAskSession(ask, greet, "record_payload_wf").run(Content.fromText("user", "hi"));

    // Assert: the record reaches the request as plain JSON.
    Object payload =
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .findFirst()
            .orElseThrow()
            .getArgs()
            .get("payload");
    assertEquals(ImmutableMap.of("days", 3L, "reason", "trip"), payload);
  }

  @Test
  public void requestInput_requestWithoutAnId_asksWithAGeneratedIdAndTheJsonPayload() {
    // Arrange
    FunctionNode<Content, String> ask =
        FunctionNodes.of(
            "ask",
            Content.class,
            String.class,
            (ctx, content) ->
                FunctionNodes.requestInput(
                    RequestInput.builder()
                        .message("Approve?")
                        .payload(new Leave(3, "trip"))
                        .build()));
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);

    // Act
    List<Event> asked =
        new JavaAskSession(ask, greet, "request_wf").run(Content.fromText("user", "hi"));

    // Assert
    Map<String, Object> args =
        asked.stream().flatMap(e -> e.functionCalls().stream()).findFirst().orElseThrow().getArgs();
    assertEquals("Approve?", args.get("message"));
    assertEquals(ImmutableMap.of("days", 3L, "reason", "trip"), args.get("payload"));
    assertTrue(args.get("interruptId") instanceof String id && !id.isEmpty());
  }

  @Test
  public void requestInput_payloadJacksonCannotWrite_failsTheAskingNode() {
    // Arrange: Jackson cannot write a bean with no properties.
    FunctionNode<Content, String> ask =
        FunctionNodes.of(
            "ask",
            Content.class,
            String.class,
            (ctx, content) -> FunctionNodes.requestInput("ask_bad", "Approve?", new Object()));
    FunctionNode<String, String> greet =
        FunctionNodes.of("greet", String.class, String.class, (ctx, name) -> "Hello, " + name);
    JavaAskSession session = new JavaAskSession(ask, greet, "bad_payload_wf");

    Content hi = Content.fromText("user", "hi");

    // Act
    Throwable error = assertThrows(Throwable.class, () -> session.run(hi));

    // Assert
    assertTrue(
        String.valueOf(error.getMessage()), error.getMessage().contains("payload of requestInput"));
  }

  @Test
  public void builder_requireConfirmation_asksThenRunsTheBodyWithTheApproval() {
    // Arrange: rerunOnResume is left unset, so the confirmation turns it on.
    AtomicInteger runs = new AtomicInteger();
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body(
                (ctx, content) -> {
                  runs.incrementAndGet();
                  return ImmutableMap.of("hint", ctx.getToolConfirmation().getHint());
                })
            .requireConfirmation(true)
            .build();
    JavaAskSession session = new JavaAskSession(remove, echo(), "confirm_wf");

    // Act
    List<Event> asked = session.run(Content.fromText("user", "rm /tmp/a"));
    List<Event> approved =
        session.run(
            answer(confirmationId(asked), ImmutableMap.of("confirmed", true, "hint", "ok")));

    // Assert
    assertEquals(
        "Please approve or reject the tool call remove().",
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .findFirst()
            .orElseThrow()
            .getArgs()
            .get("message"));
    assertEquals(ImmutableMap.of("hint", "ok"), outputOf(approved, "reply"));
    assertEquals(1, runs.get());
  }

  @Test
  public void builder_requireConfirmation_rejected_outputsTheRejectionWithoutRunningTheBody() {
    // Arrange
    AtomicInteger runs = new AtomicInteger();
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body(
                (ctx, content) -> {
                  runs.incrementAndGet();
                  return ImmutableMap.of("removed", true);
                })
            .requireConfirmation(true)
            .build();
    JavaAskSession session = new JavaAskSession(remove, echo(), "reject_wf");
    List<Event> asked = session.run(Content.fromText("user", "rm /tmp/a"));

    // Act
    List<Event> rejected =
        session.run(answer(confirmationId(asked), ImmutableMap.of("confirmed", false)));

    // Assert
    assertEquals(
        ImmutableMap.of("error", "This tool call is rejected."), outputOf(rejected, "reply"));
    assertEquals(0, runs.get());
  }

  @Test
  public void builder_requireConfirmationPredicate_asksOnlyWhenItReturnsTrue() {
    // Arrange: only a message naming /etc needs approval.
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body((ctx, content) -> ImmutableMap.of("removed", content.text()))
            .requireConfirmation((ctx, content) -> content.text().contains("/etc"))
            .build();

    // Act
    List<Event> safe =
        new JavaAskSession(remove, echo(), "safe_wf").run(Content.fromText("user", "/tmp/a"));
    List<Event> risky =
        new JavaAskSession(remove, echo(), "risky_wf").run(Content.fromText("user", "/etc/a"));

    // Assert
    assertTrue(safe.stream().allMatch(e -> e.functionCalls().isEmpty()));
    assertEquals(ImmutableMap.of("removed", "/tmp/a"), outputOf(safe, "reply"));
    assertTrue(confirmationId(risky).startsWith("wf_tool_confirmation:"));
    assertTrue(risky.stream().allMatch(e -> e.getOutput() == null));
  }

  @Test
  public void builder_requireConfirmationPredicate_runsOnceAcrossAnApprovedResume() {
    // Arrange
    AtomicInteger checks = new AtomicInteger();
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body((ctx, content) -> ImmutableMap.of("removed", content.text()))
            .requireConfirmation(
                (ctx, content) -> {
                  checks.incrementAndGet();
                  return true;
                })
            .build();
    JavaAskSession session = new JavaAskSession(remove, echo(), "predicate_once_wf");
    List<Event> asked = session.run(Content.fromText("user", "/etc/a"));

    // Act
    List<Event> approved =
        session.run(answer(confirmationId(asked), ImmutableMap.of("confirmed", true)));

    // Assert: the answer decides the resumed run, so the predicate is not asked again.
    assertEquals(ImmutableMap.of("removed", "/etc/a"), outputOf(approved, "reply"));
    assertEquals(1, checks.get());
  }

  @Test
  public void parallelOf_itemThatRequiresConfirmation_asksForEachItem() {
    // Arrange
    FunctionNode<Content, List<String>> paths =
        FunctionNodes.of(
            "paths",
            Content.class,
            new TypeReference<List<String>>() {},
            (ctx, content) -> ImmutableList.of("/etc/a", "/etc/b"));
    FunctionNode<Object, Object> remove =
        FunctionNodes.builder(Object.class, Object.class)
            .name("remove")
            .body((ctx, path) -> "removed " + path)
            .requireConfirmation(true)
            .build();

    // Act
    List<Event> asked =
        new JavaAskSession(paths, FunctionNode.parallelOf(remove), "parallel_confirm_wf")
            .run(Content.fromText("user", "go"));

    // Assert
    List<String> ids =
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .map(call -> call.getId())
            .distinct()
            .toList();
    assertEquals(2, ids.size());
    assertTrue(ids.stream().allMatch(id -> id.startsWith("wf_tool_confirmation:")));
  }

  @Test
  public void builder_requireConfirmationPredicateThrows_failsTheRunWithoutRunningTheBody() {
    // Arrange
    AtomicInteger runs = new AtomicInteger();
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body(
                (ctx, content) -> {
                  runs.incrementAndGet();
                  return ImmutableMap.of("removed", true);
                })
            .requireConfirmation(
                (ctx, content) -> {
                  throw new IOException("policy unavailable");
                })
            .build();
    JavaAskSession session = new JavaAskSession(remove, echo(), "throwing_wf");
    Content message = Content.fromText("user", "/etc/a");

    // Act
    Throwable error = assertThrows(Throwable.class, () -> session.run(message));

    // Assert
    boolean causedByPredicate = false;
    for (Throwable t = error; t != null; t = t.getCause()) {
      causedByPredicate |= "policy unavailable".equals(t.getMessage());
    }
    assertTrue(String.valueOf(error), causedByPredicate);
    assertEquals(0, runs.get());
  }

  @Test
  public void builder_requireConfirmationFalse_clearsAnEarlierPredicate() {
    // Arrange
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body((ctx, content) -> ImmutableMap.of("removed", content.text()))
            .requireConfirmation((ctx, content) -> true)
            .requireConfirmation(false)
            .build();

    // Act
    List<Event> events =
        new JavaAskSession(remove, echo(), "cleared_wf").run(Content.fromText("user", "/etc/a"));

    // Assert
    assertTrue(events.stream().allMatch(e -> e.functionCalls().isEmpty()));
    assertEquals(ImmutableMap.of("removed", "/etc/a"), outputOf(events, "reply"));
  }

  @Test
  public void toBuilder_requireConfirmationOnANodeFromOf_asks() {
    // Arrange: the node from of() does not rerun on resume, and its copy adds a confirmation.
    FunctionNode<Content, Map<String, Object>> remove =
        FunctionNodes.of(
                "remove",
                Content.class,
                JSON_OBJECT,
                (ctx, content) -> ImmutableMap.of("removed", content.text()))
            .toBuilder()
            .requireConfirmation(true)
            .build();

    // Act
    List<Event> asked =
        new JavaAskSession(remove, echo(), "copy_wf").run(Content.fromText("user", "/tmp/a"));

    // Assert
    assertTrue(confirmationId(asked).startsWith("wf_tool_confirmation:"));
    assertTrue(asked.stream().allMatch(e -> e.getOutput() == null));
  }

  @Test
  public void toBuilder_rerunOnResume_copiesOnlyAnExplicitValue() {
    // Arrange
    FunctionNode.Builder<Content, Map<String, Object>> remove =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body((ctx, content) -> ImmutableMap.of())
            .requireConfirmation(true);
    FunctionNode<Content, Map<String, Object>> derived = remove.build();
    FunctionNode<Content, Map<String, Object>> explicit = remove.rerunOnResume(true).build();

    // Act
    FunctionNode<Content, Map<String, Object>> derivedCopy =
        derived.toBuilder().requireConfirmation(false).build();
    FunctionNode<Content, Map<String, Object>> explicitCopy =
        explicit.toBuilder().requireConfirmation(false).build();

    // Assert
    assertFalse(derivedCopy.getRerunOnResume());
    assertTrue(explicitCopy.getRerunOnResume());
  }

  @Test
  public void builder_requireConfirmationRejected_failsAStringSuccessorAndUnsetsARecordsFields() {
    // Arrange
    FunctionNode<Content, String> book =
        FunctionNodes.builder(Content.class, String.class)
            .name("book")
            .body((ctx, content) -> "booked")
            .requireConfirmation(true)
            .build();
    FunctionNode<String, String> notify =
        FunctionNodes.of("notify", String.class, String.class, (ctx, text) -> text);
    FunctionNode<Leave, String> file =
        FunctionNodes.of(
            "file", Leave.class, String.class, (ctx, leave) -> leave.days() + " " + leave.reason());
    JavaAskSession stringSession = new JavaAskSession(book, notify, "string_reject_wf");
    JavaAskSession recordSession = new JavaAskSession(book, file, "record_reject_wf");
    Map<String, Object> no = ImmutableMap.of("confirmed", false);
    Content stringRejection =
        answer(confirmationId(stringSession.run(Content.fromText("user", "trip"))), no);
    Content recordRejection =
        answer(confirmationId(recordSession.run(Content.fromText("user", "trip"))), no);

    // Act
    boolean stringInputRejected = rejected(() -> stringSession.run(stringRejection));
    List<Event> recordEvents = recordSession.run(recordRejection);

    // Assert
    assertTrue(stringInputRejected);
    assertEquals("0 null", outputOf(recordEvents, "file"));
  }

  @Test
  public void builder_requireConfirmationOnARecordPort_asksWithTheInputsJsonForm() {
    // Arrange
    FunctionNode<Content, Leave> parse =
        FunctionNodes.of(
            "parse", Content.class, Leave.class, (ctx, content) -> new Leave(3, "trip"));
    FunctionNode<Leave, String> book =
        FunctionNodes.builder(Leave.class, String.class)
            .name("book")
            .body((ctx, leave) -> "booked " + leave.days())
            .requireConfirmation(true)
            .build();

    // Act
    List<Event> asked =
        new JavaAskSession(parse, book, "record_confirm_wf").run(Content.fromText("user", "hi"));

    // Assert
    Object payload =
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .findFirst()
            .orElseThrow()
            .getArgs()
            .get("payload");
    assertEquals(
        ImmutableMap.of("tool_name", "book", "args", ImmutableMap.of("days", 3L, "reason", "trip")),
        payload);
  }

  @Test
  public void builder_requireConfirmationWithRerunOnResumeFalse_failsAtBuild() {
    // Arrange
    FunctionNode.Builder<Content, Map<String, Object>> builder =
        FunctionNodes.builder(Content.class, JSON_OBJECT)
            .name("remove")
            .body((ctx, content) -> ImmutableMap.of())
            .rerunOnResume(false)
            .requireConfirmation(true);

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, builder::build);

    // Assert
    assertTrue(error.getMessage(), error.getMessage().contains("must rerun on resume"));
  }

  /** A resumable app running {@code first} then {@code second}, driven a turn at a time. */
  private static final class JavaAskSession {
    private final InMemoryRunner runner;

    JavaAskSession(FunctionNode<?, ?> first, FunctionNode<?, ?> second, String appName) {
      Workflow wf =
          Workflow.builder().name(appName).edges(g -> g.fromStart().chain(first, second)).build();
      App app =
          App.builder()
              .appName(appName)
              .rootNode(wf)
              .resumabilityConfig(new ResumabilityConfig(true))
              .build();
      runner = InMemoryRunner.builder().app(app).build();
      SessionKey key = new SessionKey(appName, "u", "s_ask");
      var unusedSession =
          AsyncJavaHelpers.await(
              c -> runner.getSessionService().createSession(key, ImmutableMap.of(), c));
    }

    List<Event> run(Content message) {
      return AsyncJavaHelpers.collect(runner.runAsync("u", "s_ask", null, message, null, null));
    }
  }

  /** The user's answer to the request {@code interruptId}. */
  private static Content answer(String interruptId, String text) {
    return answer(interruptId, ImmutableMap.of("result", text));
  }

  /** The user's answer to the request {@code interruptId}, as a {@code response} object. */
  private static Content answer(String interruptId, Map<String, Object> response) {
    return new Content(
        "user",
        ImmutableList.of(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name(RequestInput.FUNCTION_CALL_NAME)
                        .response(response)
                        .id(interruptId)
                        .build())
                .build()));
  }

  @Test
  public void scalarPorts_acceptOnlyTheJsonTypeAQuotedLiteralOrAWholeNumber() {
    // Arrange
    Object[][] rows = {
      {"3", Integer.class, "Integer:3"},
      {"1e2", Integer.class, "Integer:100"},
      {"3.0", Integer.class, "Integer:3"},
      {3.0, Integer.class, "Integer:3"},
      {3L, Long.class, "Long:3"},
      {"003", Integer.class, "fails"},
      {"+3", Integer.class, "fails"},
      {"1_000", Integer.class, "fails"},
      {" 3", Integer.class, "fails"},
      {3.5, Integer.class, "fails"},
      {"3.5", Integer.class, "fails"},
      {true, Integer.class, "fails"},
      // Quoted numbers parse as doubles, as on a Kotlin port: tiny ones round to 0, big ones lose
      // precision, and an exponent costs no time.
      {"1e-99999999", Integer.class, "Integer:0"},
      {"1.00000000000000001", Integer.class, "Integer:1"},
      {"9007199254740993.0", Long.class, "Long:9007199254740992"},
      {"3000000000", Integer.class, "fails"},
      {"300", Short.class, "Short:300"},
      {40000L, Short.class, "fails"},
      {200L, Byte.class, "fails"},
      {"1.5", Double.class, "Double:1.5"},
      {3L, Double.class, "Double:3.0"},
      {"NaN", Double.class, "fails"},
      {"Infinity", Double.class, "fails"},
      {"1e999", Double.class, "fails"},
      {1e39, Float.class, "fails"},
      {true, Double.class, "fails"},
      {"true", Boolean.class, "Boolean:true"},
      {false, Boolean.class, "Boolean:false"},
      {"TRUE", Boolean.class, "fails"},
      {"yes", Boolean.class, "fails"},
      {1L, Boolean.class, "fails"},
      {"RED", Color.class, "Color:RED"},
      {"red", Color.class, "fails"},
      {0L, Color.class, "fails"},
      // Exceptions that keep Jackson's readers.
      {" RED", Color.class, "Color:RED"},
      {65L, Character.class, "Character:A"},
    };
    Map<String, String> expected = new LinkedHashMap<>();
    Map<String, String> actual = new LinkedHashMap<>();

    // Act
    for (Object[] row : rows) {
      String key = describe(row[0]) + " -> " + ((Class<?>) row[1]).getSimpleName();
      expected.put(key, (String) row[2]);
      actual.put(key, readAs(row[0], (Class<?>) row[1]));
    }

    // Assert
    assertEquals(expected, actual);
  }

  @Test
  public void recordPort_appliesTheTableToEachField() {
    // Arrange
    Map<String, Object> typed = ImmutableMap.of("qty", 3, "rush", true);
    Map<String, Object> relaxed = ImmutableMap.of("qty", 3.0, "rush", "true", "note", "yes");
    Map<String, Object> padded = ImmutableMap.of("qty", "003", "rush", true);
    Map<String, Object> nullQty = new HashMap<>();
    nullQty.put("qty", null);
    nullQty.put("rush", true);

    Map<String, Object> bigLong = ImmutableMap.of("cents", 9007199254740993L);

    // Act
    String typedRead = readAs(typed, Rush.class);
    String relaxedRead = readAs(relaxed, Rush.class);
    String paddedRead = readAs(padded, Rush.class);
    String nullRead = readAs(nullQty, Rush.class);
    String bigLongRead = readAs(bigLong, Ledger.class);

    // Assert
    // An absent primitive keeps its default, as a Kotlin field with a default value would.
    assertEquals("Rush:Rush[qty=3, rush=true, urgent=false]", typedRead);
    assertEquals("Rush:Rush[qty=3, rush=true, urgent=false]", relaxedRead);
    assertEquals("fails", paddedRead);
    assertEquals("fails", nullRead);
    assertEquals("Ledger:Ledger[cents=9007199254740993]", bigLongRead);
  }

  @Test
  public void recordPort_stringFieldRejectsANumber() {
    // Arrange
    Map<String, Object> text = ImmutableMap.of("days", 3, "reason", "trip");
    Map<String, Object> number = ImmutableMap.of("days", 3, "reason", 5);

    // Act
    String textRead = readAs(text, Leave.class);
    String numberRead = readAs(number, Leave.class);

    // Assert
    assertEquals("Leave:Leave[days=3, reason=trip]", textRead);
    assertEquals("fails", numberRead);
  }

  /**
   * Feeds {@code value} to a node whose input port is {@code type} and returns what the node read
   * as {@code "Class:value"}, or {@code "fails"} when the port rejects it.
   */
  private static <T> String readAs(Object value, Class<T> type) {
    FunctionNode<Object, Object> feed =
        FunctionNodes.of("feed", Object.class, Object.class, (ctx, in) -> value);
    FunctionNode<T, String> read =
        FunctionNodes.of(
            "read", type, String.class, (ctx, in) -> in.getClass().getSimpleName() + ":" + in);
    try {
      return (String) outputOf(run("coercion", "go", feed, read), "read");
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof NodeInputValidationException) {
          return "fails";
        }
      }
      throw e;
    }
  }

  private static String describe(Object value) {
    return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
  }

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  /** A node named {@code reply} that outputs its input. */
  private static FunctionNode<Map<String, Object>, Map<String, Object>> echo() {
    return FunctionNodes.of("reply", JSON_OBJECT, JSON_OBJECT, (ctx, value) -> value);
  }

  /** The id of the confirmation request in {@code events}. */
  private static String confirmationId(List<Event> events) {
    return events.stream()
        .flatMap(e -> e.functionCalls().stream())
        .findFirst()
        .orElseThrow()
        .getId();
  }

  /**
   * Runs {@code steps} in sequence from START on a user {@code message} and returns every event.
   */
  private static List<Event> run(String appName, String message, Node... steps) {
    Workflow workflow =
        Workflow.builder()
            .name(appName)
            .edges(
                g -> {
                  EdgeChain chain = g.fromStart();
                  for (Node step : steps) {
                    chain = chain.then(step);
                  }
                })
            .build();
    InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(App.builder().appName(appName).rootNode(workflow).build())
            .build();
    SessionKey key = new SessionKey(appName, "user", "session");
    var unusedSession =
        AsyncJavaHelpers.await(
            c -> runner.getSessionService().createSession(key, ImmutableMap.of(), c));
    return AsyncJavaHelpers.collect(
        runner.runAsync("user", "session", null, Content.fromText("user", message), null, null));
  }

  /** Returns whether {@code run} fails because a node rejected its input. */
  private static boolean rejected(Supplier<?> run) {
    try {
      var unused = run.get();
      return false;
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof NodeInputValidationException) {
          return true;
        }
      }
      throw e;
    }
  }

  /** Returns the output of the node named {@code name} in {@code events}. */
  private static Object outputOf(List<Event> events, String name) {
    return events.stream()
        .filter(e -> e.getOutput() != null && e.getNodeInfo() != null)
        .filter(e -> e.getNodeInfo().getPath().endsWith("/" + name + "@1"))
        .findFirst()
        .orElseThrow()
        .getOutput();
  }
}
