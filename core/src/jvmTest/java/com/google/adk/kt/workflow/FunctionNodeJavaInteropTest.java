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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.adk.kt.agents.Context;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.EventActions;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureSessionService;
import com.google.adk.kt.interop.CompletableFutureSessionService;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.RunNodeConfig;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.GetSessionConfig;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.sessions.ListEventsResponse;
import com.google.adk.kt.sessions.ListSessionsResponse;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.sessions.SessionService;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.ExecutorsKt;
import kotlinx.coroutines.ThreadContextElementKt;
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
    RED,
    GREEN
  }

  record Paint(Color color) {}

  /** Written with a padded name, which it reads back. */
  enum Padded {
    @JsonProperty(" a")
    A
  }

  record Keyed(Map<Integer, String> byId) {}

  /** Holds an array in a field, since a record component should not be an array. */
  static final class Counts {
    @JsonSetter(nulls = Nulls.AS_EMPTY, contentNulls = Nulls.SKIP)
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public int[] values;

    String show() {
      return Arrays.toString(values);
    }
  }

  /** Reads a null element as 0. */
  static final class Zeroed {
    @JsonSetter(contentNulls = Nulls.AS_EMPTY)
    public int[] values;

    String show() {
      return Arrays.toString(values);
    }
  }

  record Blank(@JsonSetter(nulls = Nulls.AS_EMPTY) int n) {}

  /** Written as its ordinal wherever it appears, as its numeric shape asks. */
  @JsonFormat(shape = JsonFormat.Shape.NUMBER)
  enum Rank {
    LOW,
    HIGH
  }

  /** Each field is written as a number, through the numeric shape on its type or on itself. */
  record Ticket(
      Rank rank,
      @JsonFormat(shape = JsonFormat.Shape.NUMBER) Color color,
      @JsonFormat(shape = JsonFormat.Shape.NUMBER) boolean open,
      @JsonFormat(shape = JsonFormat.Shape.NUMBER) Boolean paid) {}

  /** Written as its {@code @JsonValue}, which wins over the numeric shape. */
  @JsonFormat(shape = JsonFormat.Shape.NUMBER)
  enum Level {
    LOW(1),
    HIGH(0);

    private final int code;

    Level(int code) {
      this.code = code;
    }

    @JsonValue
    public int code() {
      return code;
    }
  }

  /** Writes each constant as its ordinal counted from the end. */
  static final class MirroredSerializer extends StdSerializer<Enum<?>> {
    MirroredSerializer() {
      super(Enum.class, false);
    }

    @Override
    public void serialize(Enum<?> value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeNumber(value.getDeclaringClass().getEnumConstants().length - 1 - value.ordinal());
    }
  }

  /** Written as its mirrored ordinal: only {@code MID} writes its own index. */
  @JsonSerialize(using = MirroredSerializer.class)
  enum Coded {
    LOW,
    MID,
    HIGH
  }

  /** A field whose own serializer writes {@link Rank#HIGH} as 0, not as its ordinal. */
  record Flipped(@JsonSerialize(using = MirroredSerializer.class) Rank rank) {}

  /** An ARRAY shape makes Jackson write the enum as its ordinal but the boolean as a boolean. */
  record Packed(
      @JsonFormat(shape = JsonFormat.Shape.ARRAY) Color color,
      @JsonFormat(shape = JsonFormat.Shape.ARRAY) boolean lit) {}

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

  @Test
  public void body_blockingSiblingsWaitingForEachOther_bothFinish() {
    // Arrange: each body blocks until the other has started, which needs two threads.
    CountDownLatch leftStarted = new CountDownLatch(1);
    CountDownLatch rightStarted = new CountDownLatch(1);
    AtomicBoolean leftSawRight = new AtomicBoolean();
    AtomicBoolean rightSawLeft = new AtomicBoolean();
    FunctionNode<Content, Object> left =
        FunctionNodes.of(
            "left",
            Content.class,
            Object.class,
            (ctx, content) -> {
              leftStarted.countDown();
              leftSawRight.set(rightStarted.await(5, TimeUnit.SECONDS));
              return null;
            });
    FunctionNode<Content, Object> right =
        FunctionNodes.of(
            "right",
            Content.class,
            Object.class,
            (ctx, content) -> {
              rightStarted.countDown();
              rightSawLeft.set(leftStarted.await(5, TimeUnit.SECONDS));
              return null;
            });
    Workflow workflow =
        Workflow.builder().name("siblings_wf").edges(g -> g.fromStart().then(left, right)).build();

    // Act
    var unused = run(workflow, "go");

    // Assert
    assertTrue(leftSawRight.get());
    assertTrue(rightSawLeft.get());
  }

  @Test
  public void body_timedOutWithoutCatchingTheInterrupt_failsWithATimeout() {
    // Arrange: the interrupt from the timeout propagates out of the body.
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .config(NodeConfig.builder().timeoutMillis(500).build())
            .body((ctx, content) -> {
              Thread.sleep(60_000);
              throw new AssertionError("the timeout did not interrupt the body");
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("sleep_timeout_wf", "go", sleeper));

    // Assert
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
  }

  @Test
  public void body_moreParallelBodiesThanIoThreadsEachWaitingForAChild_finishOnTheDefault() {
    // Arrange: every body joins a child, so each holds a thread until its child runs; plain
    // Dispatchers.IO runs out of threads and hangs.
    int bodies = Math.max(64, Runtime.getRuntime().availableProcessors()) + 8;
    FunctionNode<Object, String> child =
        FunctionNodes.of("child", Object.class, String.class, (ctx, in) -> "c" + in);
    FunctionNode<Integer, String> waiter =
        FunctionNodes.builder(Integer.class, String.class)
            .name("waiter")
            .rerunOnResume(true)
            .body((ctx, n) ->
                String.valueOf(
                    InteropContextView.of(ctx)
                        .runNode(child, n, RunNodeConfig.builder().runId("n" + n).build())
                        .join()))
            .build();
    FunctionNode<Content, List<Integer>> items =
        FunctionNodes.of(
            "items",
            Content.class,
            new TypeReference<List<Integer>>() {},
            (ctx, message) -> IntStream.range(0, bodies).boxed().collect(toImmutableList()));
    FunctionNode<List<Integer>, List<String>> fanOut = FunctionNode.parallelOf(waiter);

    // Act: one thread collects the run, as AsyncJavaHelpers.collect does.
    List<Event> events =
        assertTimeoutPreemptively(() -> run("wide_fan_out_wf", "go", items, fanOut));

    // Assert: the fan-out's list output holds every item's result.
    assertTrue(
        events.stream()
            .anyMatch(e -> e.getOutput() instanceof List<?> list && list.size() == bodies));
  }

  @Test
  public void body_givenADispatcherThroughOfBuilderOrACopy_runsOnIt() {
    // Arrange
    AtomicReference<String> ofThread = new AtomicReference<>();
    AtomicReference<String> builderThread = new AtomicReference<>();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-body"));
    try {
      CoroutineDispatcher dispatcher = ExecutorsKt.from(executor);
      FunctionNode<Content, String> viaOf =
          FunctionNodes.of(
              "via_of",
              Content.class,
              String.class,
              dispatcher,
              (ctx, content) -> {
                ofThread.set(Thread.currentThread().getName());
                return "of";
              });
      FunctionNode<String, String> viaBuilder =
          FunctionNodes.builder(String.class, String.class, dispatcher)
              .name("via_builder")
              .body((ctx, in) -> {
                builderThread.set(Thread.currentThread().getName());
                return in + " builder";
              })
              .build();

      AtomicReference<String> copyThread = new AtomicReference<>();
      FunctionNode<String, String> viaCopy =
          viaBuilder.toBuilder()
              .name("via_copy")
              .body((ctx, in) -> {
                copyThread.set(Thread.currentThread().getName());
                return in + " copy";
              })
              .build();

      // Act
      List<Event> events = run("app_dispatcher_wf", "go", viaOf, viaBuilder, viaCopy);

      // Assert: a copy from toBuilder keeps the dispatcher too.
      assertEquals("of builder copy", outputOf(events, "via_copy"));
      assertTrue(ofThread.get(), ofThread.get().startsWith("app-body"));
      assertTrue(builderThread.get(), builderThread.get().startsWith("app-body"));
      assertTrue(copyThread.get(), copyThread.get().startsWith("app-body"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void body_onADispatcherThatRejectsIt_failsTheNodeWithTheRejection() {
    // Arrange: a shut-down executor rejects every task.
    AtomicBoolean ran = new AtomicBoolean();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.shutdown();
    FunctionNode<Content, String> body =
        FunctionNodes.of(
            "body",
            Content.class,
            String.class,
            ExecutorsKt.from(executor),
            (ctx, content) -> {
              ran.set(true);
              return "done";
            });

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("rejected_wf", "go", body));

    // Assert: the node fails with the rejection, not as a stray cancellation.
    assertFalse(String.valueOf(error), error instanceof CancellationException);
    assertFalse(ran.get());
    assertEquals(
        "RejectedExecutionException",
        sessionEvents("rejected_wf").stream()
            .filter(e -> e.getErrorCode() != null)
            .collect(onlyElement())
            .getErrorCode());
  }

  @Test
  public void body_timedOutOnAGivenDispatcher_isInterrupted() {
    // Arrange
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      FunctionNode<Content, String> sleeper =
          FunctionNodes.builder(Content.class, String.class, ExecutorsKt.from(executor))
              .name("sleeper")
              .config(NodeConfig.builder().timeoutMillis(500).build())
              .body((ctx, content) -> {
                Thread.sleep(60_000);
                throw new AssertionError("the timeout did not interrupt the body");
              })
              .build();

      // Act
      Throwable error = assertThrows(Throwable.class, () -> run("given_timeout_wf", "go", sleeper));

      // Assert
      assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void body_timedOutAndWrappingTheInterrupt_failsWithATimeout() {
    // Arrange: the body turns the interrupt from the timeout into an unchecked exception.
    FunctionNode<Content, String> wrapper =
        FunctionNodes.builder(Content.class, String.class)
            .name("wrapper")
            .config(NodeConfig.builder().timeoutMillis(500).build())
            .body((ctx, content) -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
              }
              throw new AssertionError("the timeout did not interrupt the body");
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("wrap_timeout_wf", "go", wrapper));

    // Assert
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
  }

  @Test
  public void body_timedOut_isInterruptedAndTheRunEndsOnlyAfterItReturns() {
    // Arrange: the body keeps running for a while after the interrupt.
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicBoolean returned = new AtomicBoolean();
    FunctionNode<Content, String> slow =
        FunctionNodes.builder(Content.class, String.class)
            .name("slow")
            .config(NodeConfig.builder().timeoutMillis(1_000).build())
            .body((ctx, content) -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.set(true);
              }
              long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
              while (System.nanoTime() < end) {
                Thread.onSpinWait();
              }
              returned.set(true);
              return "late";
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("timeout_wf", "go", slow));

    // Assert
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
    assertTrue(interrupted.get());
    assertTrue(returned.get());
  }

  @Test
  public void body_runCancelledWhileItBlocks_isInterruptedAndTheRunEndsOnlyAfterItReturns()
      throws InterruptedException {
    // Arrange: the body has no timeout and keeps running for a while after the interrupt.
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicBoolean returned = new AtomicBoolean();
    FunctionNode<Content, String> slow =
        FunctionNodes.of(
            "slow",
            Content.class,
            String.class,
            (ctx, content) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.set(true);
              }
              long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
              while (System.nanoTime() < end) {
                Thread.onSpinWait();
              }
              returned.set(true);
              return "late";
            });
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicBoolean returnedWhenTheRunEnded = new AtomicBoolean();
    Thread caller =
        new Thread(() -> {
          try {
            var unused = run("cancelled_run_wf", "go", slow);
          } catch (Throwable t) {
            error.set(t);
          }
          returnedWhenTheRunEnded.set(returned.get());
        });

    // Act: cancel the run by interrupting the thread blocked in AsyncJavaHelpers.collect.
    caller.start();
    try {
      assertTrue(started.await(10, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(30_000);
    } finally {
      caller.interrupt();
    }

    // Assert
    assertFalse("the run hung", caller.isAlive());
    assertTrue(String.valueOf(error.get()), causedBy(error.get(), InterruptedException.class));
    assertTrue(interrupted.get());
    assertTrue(returnedWhenTheRunEnded.get());
  }

  /** Stands in for MDC: thread-local state a caller carries into a run through its context. */
  private static final ThreadLocal<String> requestId = new ThreadLocal<>();

  @Test
  public void body_runWithACallersThreadContext_seesItOnItsWorkerThread() {
    // Arrange: the caller passes a thread-local context element, as MDCContext() is, and has an
    // OpenTelemetry context current.
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicReference<String> seenTraceId = new AtomicReference<>();
    Thread callerThread = Thread.currentThread();
    SpanContext callerSpan =
        SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    AtomicReference<Thread> thread = new AtomicReference<>();
    FunctionNode<Content, String> body =
        FunctionNodes.of(
            "body",
            Content.class,
            String.class,
            (ctx, content) -> {
              seen.set(requestId.get());
              seenTraceId.set(Span.current().getSpanContext().getTraceId());
              thread.set(Thread.currentThread());
              return "done";
            });
    Workflow workflow =
        Workflow.builder().name("context_wf").edges(g -> g.fromStart().then(body)).build();
    InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(App.builder().appName("context_wf").rootNode(workflow).build())
            .build();
    SessionKey key = new SessionKey("context_wf", "user", "session");
    var unusedSession =
        AsyncJavaHelpers.await(
            c -> runner.getSessionService().createSession(key, ImmutableMap.of(), c));

    // Act: collect on this thread, which runs the workflow but not the body.
    try (Scope unusedScope = Span.wrap(callerSpan).makeCurrent()) {
      AsyncJavaHelpers.forEach(
          runner.runAsync("user", "session", null, Content.fromText("user", "go"), null, null),
          ThreadContextElementKt.asContextElement(requestId, "request-1"),
          event -> {});
    }

    // Assert: the body ran off the caller's thread and still saw the caller's context.
    assertEquals("request-1", seen.get());
    assertEquals(callerSpan.getTraceId(), seenTraceId.get());
    assertNotEquals(callerThread, thread.get());
  }

  @Test
  public void body_timedOutThenAsksForInput_failsWithATimeoutInsteadOfWaiting() {
    // Arrange: the body swallows the interrupt from the timeout and then asks the user.
    FunctionNode<Content, String> asker =
        FunctionNodes.builder(Content.class, String.class)
            .name("asker")
            .config(NodeConfig.builder().timeoutMillis(500).build())
            .body((ctx, content) -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                // Swallowed on purpose.
              }
              return NodeBody.requestInput("late_ask", "Still there?");
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("late_ask_wf", "go", asker));

    // Assert: the timeout wins, so the run fails instead of waiting for an answer.
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
    assertTrue(
        sessionEvents("late_ask_wf").stream()
            .flatMap(e -> e.functionCalls().stream())
            .noneMatch(c -> Objects.equals(c.getId(), "late_ask")));
  }

  @Test
  public void emit_twoMessagesThenOutput_goOutInOrderWithTheirStateAndIds() {
    // Arrange
    FunctionNode<Content, String> stepper =
        FunctionNodes.of(
            "stepper",
            Content.class,
            String.class,
            (ctx, content) -> {
              ctx.updateState("before_first", 1);
              InteropContextView.of(ctx).emit(Content.fromText("model", "first"));
              ctx.updateState("before_second", 2);
              // An empty id stays empty, as in a Kotlin body.
              InteropContextView.of(ctx)
                  .emit(
                      Event.builder().id("").content(Content.fromText("model", "second")).build());
              ctx.updateState("after_second", 3);
              return "out";
            });
    SlowAppendSessionService sessions = new SlowAppendSessionService(Duration.ofMillis(30));

    // Act
    List<Event> events = eventsOf(run(workflowOf("steps_wf", stepper), "go", sessions), "stepper");

    // Assert
    assertEquals(3, events.size());
    assertEquals("first", events.get(0).getContent().text());
    assertEquals(ImmutableMap.of("before_first", 1), events.get(0).getActions().getStateDelta());
    assertEquals("second", events.get(1).getContent().text());
    assertEquals("", events.get(1).getId());
    assertEquals(ImmutableMap.of("before_second", 2), events.get(1).getActions().getStateDelta());
    assertEquals("out", events.get(2).getOutput());
    assertEquals(ImmutableMap.of("after_second", 3), events.get(2).getActions().getStateDelta());
  }

  @Test
  public void emit_readAfterEmit_seesTheBodysOwnWrite() {
    // Arrange: the store of "one" holds until the body has read, or for 50 ms, so a read made
    // before the store would miss the write.
    CountDownLatch appendStarted = new CountDownLatch(1);
    AtomicBoolean sawAppendStart = new AtomicBoolean();
    CompletableFuture<Void> bodyRead = new CompletableFuture<>();
    FunctionNode<Content, String> reader =
        FunctionNodes.of(
            "reader",
            Content.class,
            String.class,
            (ctx, content) -> {
              ctx.updateState("n", 1);
              InteropContextView.of(ctx).emit(Content.fromText("model", "one"));
              sawAppendStart.set(appendStarted.await(5, TimeUnit.SECONDS));
              String n = String.valueOf(ctx.getState().get("n"));
              bodyRead.complete(null);
              return n;
            });
    SlowAppendSessionService sessions =
        new SlowAppendSessionService(event -> {
          if (!hasText(event, "one")) {
            return CompletableFuture.completedFuture(null);
          }
          appendStarted.countDown();
          return bodyRead.completeOnTimeout(null, 50, TimeUnit.MILLISECONDS);
        });

    // Act
    List<Event> events = run(workflowOf("reader_wf", reader), "go", sessions);

    // Assert
    assertTrue(sawAppendStart.get());
    assertEquals("1", outputOf(events, "reader"));
  }

  @Test
  public void emit_parallelBodiesEmittingOverASlowStore_eachKeepsItsOrder() {
    // Arrange: two sibling bodies each emit three messages, and a barrier makes them run at once.
    CyclicBarrier bothRunning = new CyclicBarrier(2);
    FunctionNode<Content, Object> left = threeMessages("left", bothRunning);
    FunctionNode<Content, Object> right = threeMessages("right", bothRunning);
    Workflow workflow =
        Workflow.builder().name("fan_out_wf").edges(g -> g.fromStart().then(left, right)).build();
    SlowAppendSessionService sessions = new SlowAppendSessionService(Duration.ofMillis(20));

    // Act
    List<Event> events = run(workflow, "go", sessions);

    // Assert
    assertEquals(ImmutableList.of("left1", "left2", "left3"), textsOf(eventsOf(events, "left")));
    assertEquals(
        ImmutableList.of("right1", "right2", "right3"), textsOf(eventsOf(events, "right")));
  }

  @Test
  public void emit_fromAnotherThreadOfTheBody_goesOutBeforeTheOutput() {
    // Arrange
    FunctionNode<Content, String> helper =
        FunctionNodes.of(
            "helper",
            Content.class,
            String.class,
            (ctx, content) -> {
              // get() bounds the wait and rethrows a failure of the helper's emit.
              CompletableFuture.runAsync(() ->
                      InteropContextView.of(ctx).emit(Content.fromText("model", "from helper")))
                  .get(5, TimeUnit.SECONDS);
              return "out";
            });

    // Act
    List<Event> events = eventsOf(run("helper_wf", "go", helper), "helper");

    // Assert
    assertEquals(2, events.size());
    assertEquals("from helper", events.get(0).getContent().text());
    assertEquals("out", events.get(1).getOutput());
  }

  @Test
  public void emit_thenAsksForInput_sendsTheMessageOnceAndWaits() {
    // Arrange
    FunctionNode<Content, String> asker =
        FunctionNodes.of(
            "asker",
            Content.class,
            String.class,
            (ctx, content) -> {
              InteropContextView.of(ctx).emit(Content.fromText("model", "checking"));
              return NodeBody.requestInput("ask_name", "Name?");
            });
    FunctionNode<String, String> echo =
        FunctionNodes.of("echo", String.class, String.class, (ctx, s) -> s);
    JavaAskSession session = new JavaAskSession(asker, echo, "emit_then_ask_wf");

    // Act
    List<Event> asked = session.run(Content.fromText("user", "hi"));
    List<Event> answered = session.run(answer("ask_name", "Ada"));

    // Assert: the message goes out before the request, and the answer lets the run go on.
    int message = firstIndexOf(asked, e -> hasText(e, "checking"));
    int request =
        firstIndexOf(
            asked,
            e -> e.functionCalls().stream().anyMatch(c -> Objects.equals(c.getId(), "ask_name")));
    assertTrue(message >= 0);
    assertEquals(1, asked.stream().filter(e -> hasText(e, "checking")).count());
    assertTrue(request > message);
    assertTrue(answered.stream().noneMatch(e -> hasText(e, "checking")));
    assertTrue(answered.stream().map(Event::getOutput).anyMatch("Ada"::equals));
  }

  @Test
  public void emit_afterAUseAsOutputChildSentTheOutput_goesOutAfterIt() {
    // Arrange
    FunctionNode<Object, String> child =
        FunctionNodes.of("child", Object.class, String.class, (ctx, in) -> "from the child");
    FunctionNode<Content, String> parent =
        FunctionNodes.builder(Content.class, String.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, content) -> {
              InteropContextView.of(ctx)
                  .runNode(child, null, RunNodeConfig.builder().useAsOutput(true).build())
                  .join();
              InteropContextView.of(ctx).emit(Content.fromText("model", "after the child"));
              return "ignored";
            })
            .build();

    // Act
    List<Event> events = run("emit_after_output_wf", "go", parent);

    // Assert: the child's output stays the parent's, and the message follows it.
    int output = firstIndexOf(events, e -> Objects.equals(e.getOutput(), "from the child"));
    int message = firstIndexOf(events, e -> hasText(e, "after the child"));
    assertTrue(output >= 0);
    assertTrue(message > output);
    assertTrue(events.stream().noneMatch(e -> Objects.equals(e.getOutput(), "ignored")));
  }

  @Test
  public void emit_fromANestedJavaChildWhileTheParentWaits_goesOutBeforeTheParentsOutput() {
    // Arrange
    FunctionNode<Object, String> child =
        FunctionNodes.of(
            "child",
            Object.class,
            String.class,
            (ctx, in) -> {
              InteropContextView.of(ctx).emit(Content.fromText("model", "from the child"));
              return "child out";
            });
    FunctionNode<Content, String> parent =
        FunctionNodes.builder(Content.class, String.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, content) -> "got " + InteropContextView.of(ctx).runNode(child).join())
            .build();

    // Act
    List<Event> events = run("nested_emit_wf", "go", parent);

    // Assert: the message goes out as the child's event, before the parent's output.
    int message = firstIndexOf(events, e -> hasText(e, "from the child"));
    int output = firstIndexOf(events, e -> Objects.equals(e.getOutput(), "got child out"));
    assertTrue(message >= 0);
    assertTrue(output > message);
    assertTrue(eventsOf(events, "child").stream().anyMatch(e -> hasText(e, "from the child")));
    assertTrue(eventsOf(events, "parent").stream().noneMatch(e -> hasText(e, "from the child")));
  }

  @Test
  public void emit_eventWithAnOutput_throwsAndTheBodyMayCatchIt() {
    // Arrange: as in a Kotlin node {} body, the rejection reaches only the call.
    AtomicReference<IllegalArgumentException> seen = new AtomicReference<>();
    FunctionNode<Content, String> careful =
        FunctionNodes.of(
            "careful",
            Content.class,
            String.class,
            (ctx, content) -> {
              try {
                InteropContextView.of(ctx).emit(Event.builder().output("x").build());
              } catch (IllegalArgumentException e) {
                seen.set(e);
              }
              return "out";
            });

    // Act
    List<Event> events = run("careful_wf", "go", careful);

    // Assert
    assertTrue(seen.get().getMessage(), seen.get().getMessage().contains("return the value"));
    assertEquals("out", outputOf(events, "careful"));
  }

  @Test
  public void emit_afterTheBodyReturned_throws() {
    // Arrange
    AtomicReference<Context> kept = new AtomicReference<>();
    FunctionNode<Content, String> keep =
        FunctionNodes.of(
            "keep",
            Content.class,
            String.class,
            (ctx, content) -> {
              kept.set(ctx);
              return "out";
            });
    var unused = run("keep_wf", "go", keep);

    Context context = kept.get();
    Content tooLate = Content.fromText("model", "too late");

    // Act
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> InteropContextView.of(context).emit(tooLate));

    // Assert
    assertTrue(error.getMessage(), error.getMessage().contains("only while a Java FunctionNode"));
  }

  @Test
  public void emit_childBodyRunOnTheCallersContext_failsTheChildAndKeepsTheCallersEmit() {
    // Arrange: the caller collects the child's Node.runNode flow on its own Context, so both bodies
    // would share one node state.
    FunctionNode<Object, String> child =
        FunctionNodes.of("child", Object.class, String.class, (ctx, input) -> "child out");
    AtomicReference<IllegalStateException> childError = new AtomicReference<>();
    FunctionNode<Content, String> caller =
        FunctionNodes.of(
            "caller",
            Content.class,
            String.class,
            (ctx, content) -> {
              try {
                AsyncJavaHelpers.forEach(child.runNode(ctx, "x"), item -> {});
              } catch (IllegalStateException e) {
                childError.set(e);
              }
              InteropContextView.of(ctx).emit(Content.fromText("model", "from the caller"));
              return "out";
            });

    // Act
    List<Event> events = eventsOf(run("shared_wf", "go", caller), "caller");

    // Assert
    assertNotNull(childError.get());
    assertTrue(
        childError.get().getMessage(),
        childError.get().getMessage().contains("already runs with the Context of node 'caller'"));
    assertEquals("from the caller", events.get(0).getContent().text());
    assertEquals("out", events.get(1).getOutput());
  }

  @Test
  public void emit_nodeCancelledWhileItWaits_throwsCancellationAndSoDoesALaterCall() {
    // Arrange: the store of "wait" holds until the body has returned, past the node's timeout.
    CompletableFuture<Void> bodyReturned = new CompletableFuture<>();
    AtomicReference<CancellationException> cancelled = new AtomicReference<>();
    AtomicReference<CancellationException> laterCancelled = new AtomicReference<>();
    FunctionNode<Content, String> stuck =
        FunctionNodes.builder(Content.class, String.class)
            .name("stuck")
            .config(NodeConfig.builder().timeoutMillis(500).build())
            .body((ctx, content) -> {
              try {
                InteropContextView.of(ctx).emit(Content.fromText("model", "wait"));
              } catch (CancellationException e) {
                cancelled.set(e);
              }
              try {
                InteropContextView.of(ctx).emit(Content.fromText("model", "after"));
              } catch (CancellationException e) {
                laterCancelled.set(e);
              }
              bodyReturned.complete(null);
              return "late";
            })
            .build();
    SlowAppendSessionService sessions =
        new SlowAppendSessionService(event ->
            hasText(event, "wait")
                ? bodyReturned.orTimeout(10, TimeUnit.SECONDS)
                : CompletableFuture.completedFuture(null));

    Workflow workflow = workflowOf("stuck_wf", stuck);

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run(workflow, "go", sessions));

    // Assert
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
    assertNotNull(cancelled.get());
    assertNotNull(laterCancelled.get());
    List<Event> stored = sessionEvents("stuck_wf");
    assertFalse(stored.stream().anyMatch(e -> hasText(e, "after")));
    assertFalse(stored.stream().anyMatch(e -> Objects.equals(e.getOutput(), "late")));
  }

  @Test
  public void emit_everyCallAfterACancel_throws() {
    // Arrange: the body sleeps until the timeout interrupts it, swallows the interrupt and emits.
    AtomicInteger cancels = new AtomicInteger();
    FunctionNode<Content, String> looper =
        FunctionNodes.builder(Content.class, String.class)
            .name("looper")
            .config(NodeConfig.builder().timeoutMillis(300).build())
            .body((ctx, content) -> {
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                // Swallows the timeout's interrupt, as some I/O does, and keeps going.
              }
              try {
                // Only the cancel check stops a partial event right after the cancel.
                InteropContextView.of(ctx)
                    .emit(
                        Event.builder()
                            .partial(true)
                            .content(Content.fromText("model", "part"))
                            .build());
              } catch (CancellationException e) {
                cancels.incrementAndGet();
              }
              for (int i = 0; i < 3; i++) {
                try {
                  InteropContextView.of(ctx).emit(Content.fromText("model", "batch"));
                } catch (CancellationException e) {
                  cancels.incrementAndGet();
                }
              }
              return "late";
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("looper_wf", "go", looper));

    // Assert: every emit after the cancel throws, and no batch is stored.
    assertTrue(String.valueOf(error), causedBy(error, NodeTimeoutException.class));
    assertEquals(4, cancels.get());
    assertFalse(sessionEvents("looper_wf").stream().anyMatch(e -> hasText(e, "batch")));
  }

  @Test
  public void emit_failingFromAHelperWhileTheBodyWaitsForAChild_cancelsTheChild() {
    // Arrange: the helper's event writes a state key the schema lacks, which fails the node. The
    // body joins a child that sleeps, and join() ignores the interrupt, so only a cancel of the
    // child lets the body return before the child's sleep ends.
    CountDownLatch childStarted = new CountDownLatch(1);
    AtomicBoolean childInterrupted = new AtomicBoolean();
    CompletableFuture<Void> helperDone = new CompletableFuture<>();
    AtomicReference<IllegalArgumentException> helperError = new AtomicReference<>();
    FunctionNode<Object, String> sleeper =
        FunctionNodes.of(
            "sleeper",
            Object.class,
            String.class,
            (ctx, in) -> {
              childStarted.countDown();
              try {
                Thread.sleep(20_000);
                return "slept";
              } catch (InterruptedException e) {
                childInterrupted.set(true);
                throw e;
              }
            });
    Schema state =
        Schema.builder()
            .type(Type.OBJECT)
            .properties(ImmutableMap.of("k", Schema.builder().type(Type.INTEGER).build()))
            .build();
    Event undeclaredWrite =
        Event.builder()
            .actions(
                EventActions.builder()
                    .stateDelta(new HashMap<>(ImmutableMap.of("undeclared", 1)))
                    .build())
            .build();
    FunctionNode<Content, String> parent =
        FunctionNodes.builder(Content.class, String.class)
            .name("parent")
            .rerunOnResume(true)
            .stateSchema(state)
            .body((ctx, content) -> {
              Thread helper =
                  new Thread(() -> {
                    try {
                      if (childStarted.await(5, TimeUnit.SECONDS)) {
                        InteropContextView.of(ctx).emit(undeclaredWrite);
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } catch (IllegalArgumentException e) {
                      helperError.set(e);
                    } finally {
                      helperDone.complete(null);
                    }
                  });
              helper.start();
              return String.valueOf(InteropContextView.of(ctx).runNode(sleeper, "x").join());
            })
            .build();

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("schema_wf", "go", parent));
    helperDone.orTimeout(5, TimeUnit.SECONDS).join();

    // Assert: the node, its child and the helper's call all end with the failure.
    assertTrue(String.valueOf(error), causedBy(error, IllegalArgumentException.class));
    assertTrue(childInterrupted.get());
    assertNotNull(helperError.get());
  }

  @Test
  public void emit_helperInterruptedWhileItWaits_throwsCancellationAndTheEventStillGoesOut() {
    // Arrange: the store of "helper" holds until the interrupted helper has left its emit.
    CountDownLatch appendStarted = new CountDownLatch(1);
    CompletableFuture<Void> releaseStore = new CompletableFuture<>();
    AtomicReference<String> helperOutcome = new AtomicReference<>();
    AtomicBoolean helperKeptInterrupt = new AtomicBoolean();
    FunctionNode<Content, String> parent =
        FunctionNodes.of(
            "parent",
            Content.class,
            String.class,
            (ctx, content) -> {
              Thread helper =
                  new Thread(() -> {
                    try {
                      InteropContextView.of(ctx).emit(Content.fromText("model", "helper"));
                      helperOutcome.set("returned");
                    } catch (CancellationException e) {
                      helperOutcome.set("cancelled");
                    }
                    helperKeptInterrupt.set(Thread.currentThread().isInterrupted());
                  });
              helper.start();
              assertTrue(appendStarted.await(5, TimeUnit.SECONDS));
              helper.interrupt();
              helper.join(5_000);
              releaseStore.complete(null);
              InteropContextView.of(ctx).emit(Content.fromText("model", "after"));
              return "out";
            });
    SlowAppendSessionService sessions =
        new SlowAppendSessionService(event -> {
          if (!hasText(event, "helper")) {
            return CompletableFuture.completedFuture(null);
          }
          appendStarted.countDown();
          return releaseStore.completeOnTimeout(null, 10, TimeUnit.SECONDS);
        });

    // Act
    List<Event> events =
        eventsOf(run(workflowOf("interrupt_wf", parent), "go", sessions), "parent");

    // Assert: the interrupt ends only the helper's wait; the node goes on, so its event goes out.
    assertEquals("cancelled", helperOutcome.get());
    assertTrue(helperKeptInterrupt.get());
    assertEquals(3, events.size());
    assertTrue(hasText(events.get(0), "helper"));
    assertTrue(hasText(events.get(1), "after"));
    assertEquals("out", events.get(2).getOutput());
  }

  @Test
  public void emit_helperWaitingWhenTheBodyFails_returnsAndItsEventGoesOut() {
    // Arrange: the body fails while a helper's message waits for its store.
    CountDownLatch appendStarted = new CountDownLatch(1);
    CompletableFuture<Void> helperDone = new CompletableFuture<>();
    AtomicReference<String> helperOutcome = new AtomicReference<>();
    FunctionNode<Content, String> failing =
        FunctionNodes.of(
            "failing",
            Content.class,
            String.class,
            (ctx, content) -> {
              Thread helper =
                  new Thread(() -> {
                    try {
                      InteropContextView.of(ctx).emit(Content.fromText("model", "helper"));
                      helperOutcome.set("returned");
                    } catch (CancellationException e) {
                      helperOutcome.set("cancelled");
                    } finally {
                      helperDone.complete(null);
                    }
                  });
              helper.start();
              assertTrue(appendStarted.await(5, TimeUnit.SECONDS));
              throw new IllegalStateException("boom");
            });
    SlowAppendSessionService sessions =
        new SlowAppendSessionService(event -> {
          if (!hasText(event, "helper")) {
            return CompletableFuture.completedFuture(null);
          }
          appendStarted.countDown();
          return CompletableFuture.runAsync(
              () -> {}, CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));
        });
    Workflow workflow = workflowOf("failing_wf", failing);

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run(workflow, "go", sessions));
    helperDone.orTimeout(5, TimeUnit.SECONDS).join();

    // Assert: as in ADK Java, the accepted message goes out before the failure.
    assertTrue(String.valueOf(error), causedBy(error, IllegalStateException.class));
    assertEquals("returned", helperOutcome.get());
    assertTrue(sessionEvents("failing_wf").stream().anyMatch(e -> hasText(e, "helper")));
  }

  @Test
  public void emit_partialEvent_doesNotWaitForAnEarlierCallsStore() {
    // Arrange: a helper's message holds its store until the body's later partial event returns.
    CountDownLatch appendStarted = new CountDownLatch(1);
    CompletableFuture<Void> releaseStore = new CompletableFuture<>();
    AtomicBoolean partialReturnedFirst = new AtomicBoolean();
    FunctionNode<Content, String> streamer =
        FunctionNodes.of(
            "streamer",
            Content.class,
            String.class,
            (ctx, content) -> {
              CompletableFuture<Void> helper =
                  CompletableFuture.runAsync(
                      () -> InteropContextView.of(ctx).emit(Content.fromText("model", "slow")));
              try {
                assertTrue(appendStarted.await(5, TimeUnit.SECONDS));
                InteropContextView.of(ctx)
                    .emit(
                        Event.builder()
                            .partial(true)
                            .content(Content.fromText("model", "part"))
                            .build());
                partialReturnedFirst.set(!releaseStore.isDone());
              } finally {
                releaseStore.complete(null);
              }
              helper.get(5, TimeUnit.SECONDS);
              return "out";
            });
    SlowAppendSessionService sessions =
        new SlowAppendSessionService(event -> {
          if (!hasText(event, "slow")) {
            return CompletableFuture.completedFuture(null);
          }
          appendStarted.countDown();
          return releaseStore.completeOnTimeout(null, 10, TimeUnit.SECONDS);
        });
    Workflow workflow = workflowOf("streaming_wf", streamer);

    // Act
    List<Event> events = eventsOf(run(workflow, "go", sessions), "streamer");

    // Assert: the partial event still goes out after the earlier message.
    assertTrue(partialReturnedFirst.get());
    assertEquals(ImmutableList.of("slow", "part"), textsOf(events.subList(0, 2)));
  }

  @Test
  public void emit_collectedOnASingleThread_doesNotStarveTheRun() {
    // Arrange: one thread collects the run while the body runs on the default dispatcher, so a body
    // waiting in emit must not occupy the collector's thread.
    FunctionNode<Content, String> onDefault =
        FunctionNodes.of(
            "on_default",
            Content.class,
            String.class,
            (ctx, content) -> {
              InteropContextView.of(ctx).emit(Content.fromText("model", "progress"));
              return "out";
            });
    InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(
                App.builder()
                    .appName("single_collector_wf")
                    .rootNode(workflowOf("single_collector_wf", onDefault))
                    .build())
            .build();
    SessionKey key = new SessionKey("single_collector_wf", "user", "session");
    var unusedSession =
        AsyncJavaHelpers.await(
            c -> runner.getSessionService().createSession(key, ImmutableMap.of(), c));
    ExecutorService collector = Executors.newSingleThreadExecutor();
    try {
      // Act
      List<Event> events =
          assertTimeoutPreemptively(() ->
              AsyncJavaHelpers.collect(
                  runner.runAsync(
                      "user", "session", null, Content.fromText("user", "go"), null, null),
                  ExecutorsKt.from(collector)));

      // Assert
      List<Event> own = eventsOf(events, "on_default");
      assertEquals(2, own.size());
      assertEquals("progress", own.get(0).getContent().text());
      assertEquals("out", own.get(1).getOutput());
    } finally {
      collector.shutdownNow();
    }
  }

  @Test
  public void emit_partialEvent_reachesTheCallerWithoutStateAndIsNotStored() {
    // Arrange
    FunctionNode<Content, String> streamer =
        FunctionNodes.of(
            "streamer",
            Content.class,
            String.class,
            (ctx, content) -> {
              ctx.updateState("k", 1);
              InteropContextView.of(ctx)
                  .emit(
                      Event.builder()
                          .partial(true)
                          .content(Content.fromText("model", "part"))
                          .build());
              return "out";
            });

    // Act
    List<Event> events = eventsOf(run(workflowOf("partial_wf", streamer), "go"), "streamer");

    // Assert
    assertEquals(2, events.size());
    assertTrue(events.get(0).getPartial());
    assertTrue(hasText(events.get(0), "part"));
    assertEquals(ImmutableMap.of(), events.get(0).getActions().getStateDelta());
    assertEquals(ImmutableMap.of("k", 1), events.get(1).getActions().getStateDelta());
    assertFalse(sessionEvents("partial_wf").stream().anyMatch(e -> hasText(e, "part")));
  }

  @Test
  public void body_interruptedWithoutACancel_failsTheNodeWithTheInterrupt() {
    // Arrange: the body interrupts itself, as a helper thread would.
    FunctionNode<Content, String> sleeper =
        FunctionNodes.of(
            "sleeper",
            Content.class,
            String.class,
            (ctx, content) -> {
              Thread.currentThread().interrupt();
              Thread.sleep(10_000);
              throw new AssertionError("the interrupt did not reach the body");
            });

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("interrupted_wf", "go", sleeper));

    // Assert: the interrupt fails the node as itself, and does not cancel the run.
    assertTrue(String.valueOf(error), error instanceof InterruptedException);
    assertEquals(
        "InterruptedException",
        sessionEvents("interrupted_wf").stream()
            .filter(e -> e.getErrorCode() != null)
            .collect(onlyElement())
            .getErrorCode());
  }

  @Test
  public void body_throwsCancellationWithoutACancel_failsTheNode() {
    // Arrange: the body throws a CancellationException, as join() on a cancelled future does.
    FunctionNode<Content, String> joiner =
        FunctionNodes.of(
            "joiner",
            Content.class,
            String.class,
            (ctx, content) -> {
              throw new CancellationException("cancelled future");
            });

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("cancelled_future_wf", "go", joiner));

    // Assert: the exception fails the node with it as cause, and does not cancel the run.
    assertFalse(String.valueOf(error), error instanceof CancellationException);
    assertTrue(String.valueOf(error), causedBy(error, NodeExecutionException.class));
    // Coroutine stack-trace recovery may hand back a copy, so look along the cause chain.
    assertEquals("cancelled future", causeOf(error, CancellationException.class).getMessage());
    Event errorEvent =
        sessionEvents("cancelled_future_wf").stream()
            .filter(e -> e.getErrorCode() != null)
            .collect(onlyElement());
    assertEquals("NodeExecutionException", errorEvent.getErrorCode());
    assertEquals(
        "The node body threw a CancellationException without a cancel of the node.",
        errorEvent.getErrorMessage());
  }

  @Test
  public void body_throwsAWrappedFailure_failsAndRetriesUnderTheCausesName() {
    // Arrange: the first attempt fails as join() does, with the cause wrapped.
    AtomicInteger attempts = new AtomicInteger();
    FunctionNode<Content, String> flaky =
        FunctionNodes.builder(Content.class, String.class)
            .name("flaky")
            .config(
                NodeConfig.builder()
                    .retryConfig(
                        RetryConfig.builder()
                            .maxAttempts(2)
                            .initialDelayMillis(0)
                            .exceptions("IllegalStateException")
                            .build())
                    .build())
            .body((ctx, content) -> {
              if (attempts.incrementAndGet() == 1) {
                throw new CompletionException(new IllegalStateException("busy"));
              }
              return "done";
            })
            .build();

    // Act
    List<Event> events = run("wrapped_failure_wf", "go", flaky);

    // Assert: the retry matched the cause's name, and the failed attempt reported that name.
    assertEquals("done", outputOf(events, "flaky"));
    assertEquals(2, attempts.get());
    assertEquals(
        "IllegalStateException",
        sessionEvents("wrapped_failure_wf").stream()
            .filter(e -> e.getErrorCode() != null)
            .collect(onlyElement())
            .getErrorCode());
  }

  @Test
  public void body_throwsAWrappedCancellationWithoutACancel_failsTheNode() {
    // Arrange: a composed future wraps a cancellation in a CompletionException.
    FunctionNode<Content, String> joiner =
        FunctionNodes.of(
            "joiner",
            Content.class,
            String.class,
            (ctx, content) -> {
              throw new CompletionException(new CancellationException("composed"));
            });

    // Act
    Throwable error = assertThrows(Throwable.class, () -> run("composed_cancel_wf", "go", joiner));

    // Assert: unwrapped, the cancellation still fails the node instead of cancelling the run.
    assertFalse(String.valueOf(error), error instanceof CancellationException);
    assertTrue(String.valueOf(error), causedBy(error, NodeExecutionException.class));
    assertEquals(
        "NodeExecutionException",
        sessionEvents("composed_cancel_wf").stream()
            .filter(e -> e.getErrorCode() != null)
            .collect(onlyElement())
            .getErrorCode());
  }

  @Test
  public void body_waitingOnAChildThatAsksForInput_waitsThenGetsTheAnswer() {
    // Arrange: the body blocks on a child that pauses, as join() wraps the pause.
    FunctionNode<Object, String> asker =
        FunctionNodes.of(
            "asker",
            Object.class,
            String.class,
            (ctx, in) -> NodeBody.requestInput("ask_name", "Name?"));
    FunctionNode<Content, String> parent =
        FunctionNodes.builder(Content.class, String.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, content) -> "Hello, " + InteropContextView.of(ctx).runNode(asker).join())
            .build();
    FunctionNode<String, String> echo =
        FunctionNodes.of("echo", String.class, String.class, (ctx, s) -> s);
    JavaAskSession session = new JavaAskSession(parent, echo, "child_asks_wf");

    // Act
    List<Event> asked = session.run(Content.fromText("user", "hi"));
    List<Event> answered = session.run(answer("ask_name", "Ada"));

    // Assert: the first turn asks instead of failing, and the answer reaches the body.
    assertTrue(asked.stream().allMatch(e -> e.getErrorCode() == null));
    assertTrue(
        asked.stream()
            .flatMap(e -> e.functionCalls().stream())
            .anyMatch(c -> Objects.equals(c.getId(), "ask_name")));
    assertTrue(answered.stream().map(Event::getOutput).anyMatch("Hello, Ada"::equals));
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
      // An enum written as its ordinal reads no other number.
      {2L, Rank.class, "fails"},
      {-1L, Rank.class, "fails"},
      {1.0, Rank.class, "fails"},
      // An enum whose numbers are not its ordinals reads none of them.
      {0L, Coded.class, "fails"},
      {1L, Coded.class, "fails"},
      {2L, Coded.class, "fails"},
      // An enum reads a padded name only where Jackson writes it.
      {" RED", Color.class, "fails"},
      {"RED\u0001", Color.class, "fails"},
      {" a", Padded.class, "Padded:A"},
      // A char reads one character of text, not a code point.
      {"A", Character.class, "Character:A"},
      {65L, Character.class, "fails"},
      {"AB", Character.class, "fails"},
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
    Map<String, Object> nullN = new HashMap<>();
    nullN.put("n", null);

    Map<String, Object> bigLong = ImmutableMap.of("cents", 9007199254740993L);
    ImmutableMap<String, Object> numericRush = ImmutableMap.of("qty", 3, "rush", 1L);
    ImmutableMap<String, Object> numericColor = ImmutableMap.of("color", 1L);
    ImmutableMap<String, Object> outOfRange =
        ImmutableMap.of("rank", 1L, "color", 1L, "open", 2L, "paid", 0L);
    ImmutableMap<String, Object> flipped = ImmutableMap.of("rank", 0L);

    // Act
    String typedRead = readAs(typed, Rush.class);
    String relaxedRead = readAs(relaxed, Rush.class);
    String paddedRead = readAs(padded, Rush.class);
    String nullRead = readAs(nullQty, Rush.class);
    String nullAsEmptyRead = readAs(nullN, Blank.class);
    String bigLongRead = readAs(bigLong, Ledger.class);
    String numericRushRead = readAs(numericRush, Rush.class);
    String numericColorRead = readAs(numericColor, Paint.class);
    String outOfRangeRead = readAs(outOfRange, Ticket.class);
    String flippedRead = readAs(flipped, Flipped.class);

    // Assert
    // An absent primitive keeps its default, as a Kotlin field with a default value would.
    assertEquals("Rush:Rush[qty=3, rush=true, urgent=false]", typedRead);
    assertEquals("Rush:Rush[qty=3, rush=true, urgent=false]", relaxedRead);
    assertEquals("fails", paddedRead);
    assertEquals("fails", nullRead);
    // A primitive field set to read null as empty reads its default.
    assertEquals("Blank:Blank[n=0]", nullAsEmptyRead);
    assertEquals("Ledger:Ledger[cents=9007199254740993]", bigLongRead);
    // A plain boolean or enum field still rejects a number.
    assertEquals("fails", numericRushRead);
    assertEquals("fails", numericColorRead);
    // A boolean field written as 1 or 0 reads no other number.
    assertEquals("fails", outOfRangeRead);
    // A field with its own serializer keeps rejecting numbers, whatever its type's shape.
    assertEquals("fails", flippedRead);
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

  @Test
  public void primitiveArrayPorts_readEachElementAsItsTypeDoes() {
    // Arrange
    Map<String, Object> nullField = new HashMap<>();
    nullField.put("values", null);

    // Act
    String literals = readAs(ImmutableList.of("3", 2.0, "1e2"), int[].class, Arrays::toString);
    String paddedNumber = readAs(ImmutableList.of("003"), int[].class, Arrays::toString);
    String nullElement = readAs(Arrays.asList(1, null), int[].class, Arrays::toString);
    String booleans = readAs(ImmutableList.of("true", false), boolean[].class, Arrays::toString);
    String numberAsBoolean = readAs(ImmutableList.of(1L), boolean[].class, Arrays::toString);
    String fieldElement =
        readAs(ImmutableMap.of("values", ImmutableList.of(" 4")), Counts.class, Counts::show);
    String nullFieldRead = readAs(nullField, Counts.class, Counts::show);
    String skippedElement =
        readAs(ImmutableMap.of("values", Arrays.asList(1, null, 2)), Counts.class, Counts::show);
    String singleValue = readAs(ImmutableMap.of("values", 5L), Counts.class, Counts::show);
    String zeroedElement =
        readAs(ImmutableMap.of("values", Arrays.asList(1, null)), Zeroed.class, Zeroed::show);

    // Assert
    assertEquals("[3, 2, 100]", literals);
    assertEquals("fails", paddedNumber);
    assertEquals("fails", nullElement);
    assertEquals("[true, false]", booleans);
    assertEquals("fails", numberAsBoolean);
    assertEquals("fails", fieldElement);
    // A field's own null settings still apply.
    assertEquals("[]", nullFieldRead);
    assertEquals("[1, 2]", skippedElement);
    assertEquals("[5]", singleValue);
    assertEquals("[1, 0]", zeroedElement);
  }

  @Test
  public void mapKeys_readAsValuesOfTheirTypeDo() {
    // Arrange
    Map<String, Object> plain = ImmutableMap.of("byId", ImmutableMap.of("3", "a"));
    Map<String, Object> exponent = ImmutableMap.of("byId", ImmutableMap.of("1e2", "a"));
    Map<String, Object> whole = ImmutableMap.of("byId", ImmutableMap.of("3.0", "a"));
    Map<String, Object> padded = ImmutableMap.of("byId", ImmutableMap.of("003", "a"));
    Map<String, Object> fraction = ImmutableMap.of("byId", ImmutableMap.of("3.5", "a"));

    // Act
    String plainRead = readAs(plain, Keyed.class);
    String exponentRead = readAs(exponent, Keyed.class);
    String wholeRead = readAs(whole, Keyed.class);
    String paddedRead = readAs(padded, Keyed.class);
    String fractionRead = readAs(fraction, Keyed.class);

    // Assert
    assertEquals("Keyed:Keyed[byId={3=a}]", plainRead);
    assertEquals("Keyed:Keyed[byId={100=a}]", exponentRead);
    assertEquals("Keyed:Keyed[byId={3=a}]", wholeRead);
    assertEquals("fails", paddedRead);
    assertEquals("fails", fractionRead);
  }

  @Test
  public void typedPort_jacksonAnnotatedValue_roundTripsToASuccessorTypedTheSame() {
    // Arrange: each value and the JSON form its typed node writes.
    Object[][] rows = {
      // Jackson writes a number: under a numeric shape, or ARRAY on an enum.
      {Rank.HIGH, 1L},
      {
        new Ticket(Rank.HIGH, Color.GREEN, true, false),
        ImmutableMap.of("rank", 1L, "color", 1L, "open", 1L, "paid", 0L)
      },
      {new Packed(Color.GREEN, true), ImmutableMap.of("color", 1L, "lit", true)},
      // Jackson writes the @JsonValue, which wins over a numeric shape; HIGH's 0 is LOW's ordinal.
      {Level.HIGH, 0L},
    };
    Map<String, Object> expected = new LinkedHashMap<>();
    Map<String, Object> actual = new LinkedHashMap<>();

    // Act
    for (Object[] row : rows) {
      String value = row[0].getClass().getSimpleName() + ":" + row[0];
      expected.put(value, ImmutableList.of(row[1], value));
      actual.put(value, roundTrip(row[0]));
    }

    // Assert
    assertEquals(expected, actual);
  }

  /**
   * Feeds {@code value} to a node whose input port is {@code type} and returns what the node read
   * as {@code "Class:value"}, or {@code "fails"} when the port rejects it.
   */
  private <T> String readAs(Object value, Class<T> type) {
    return readAs(value, type, in -> in.getClass().getSimpleName() + ":" + in);
  }

  /** Like {@link #readAs(Object, Class)}, but returns what the node read as {@code show} does. */
  private <T> String readAs(Object value, Class<T> type, Function<? super T, String> show) {
    FunctionNode<Object, Object> feed =
        FunctionNodes.of("feed", Object.class, Object.class, (ctx, in) -> value);
    FunctionNode<T, String> read =
        FunctionNodes.of("read", type, String.class, (ctx, in) -> show.apply(in));
    try {
      return (String) outputOf(run("coercion", "go", feed, read), "read");
    } catch (RuntimeException e) {
      if (isRejection(e)) {
        return "fails";
      }
      throw e;
    }
  }

  /**
   * Outputs {@code value} from a node typed as its class into a successor typed the same, and
   * returns what the node wrote and what the successor read as {@code "Class:value"}, or {@code
   * "fails"} when the successor rejects it.
   */
  private <T> Object roundTrip(T value) {
    @SuppressWarnings("unchecked") // The class of a value is its type.
    Class<T> type = (Class<T>) value.getClass();
    FunctionNode<Content, T> write =
        FunctionNodes.of("write", Content.class, type, (ctx, m) -> value);
    FunctionNode<T, String> read =
        FunctionNodes.of(
            "read", type, String.class, (ctx, in) -> in.getClass().getSimpleName() + ":" + in);
    try {
      List<Event> events = run("round_trip", "go", write, read);
      return ImmutableList.of(outputOf(events, "write"), outputOf(events, "read"));
    } catch (RuntimeException e) {
      if (isRejection(e)) {
        return "fails";
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
  private List<Event> run(String appName, String message, Node... steps) {
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
    return run(workflow, message);
  }

  /** Runs {@code workflow} as an app on a user {@code message} and returns every event. */
  private List<Event> run(Workflow workflow, String message) {
    return run(workflow, message, new InMemorySessionService());
  }

  /** Like {@link #run(Workflow, String)}, storing events in {@code sessions}. */
  private List<Event> run(Workflow workflow, String message, SessionService sessions) {
    String appName = workflow.getName();
    InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(App.builder().appName(appName).rootNode(workflow).build())
            .sessionService(sessions)
            .build();
    runners.put(appName, runner);
    SessionKey key = new SessionKey(appName, "user", "session");
    var unusedSession =
        AsyncJavaHelpers.await(
            c -> runner.getSessionService().createSession(key, ImmutableMap.of(), c));
    return AsyncJavaHelpers.collect(
        runner.runAsync("user", "session", null, Content.fromText("user", message), null, null));
  }

  /** A workflow that runs {@code node} from START. */
  private static Workflow workflowOf(String name, Node node) {
    return Workflow.builder().name(name).edges(g -> g.fromStart().then(node)).build();
  }

  /** Returns the events of the first run of the node named {@code name}, in order. */
  private static List<Event> eventsOf(List<Event> events, String name) {
    return events.stream()
        .filter(e -> e.getNodeInfo() != null)
        .filter(e -> e.getNodeInfo().getPath().endsWith("/" + name + "@1"))
        .collect(toImmutableList());
  }

  /**
   * A body named {@code name} that waits for {@code start}, then emits {@code name1} to {@code
   * name3} and has no output.
   */
  private static FunctionNode<Content, Object> threeMessages(String name, CyclicBarrier start) {
    return FunctionNodes.of(
        name,
        Content.class,
        Object.class,
        (ctx, content) -> {
          start.await(5, TimeUnit.SECONDS);
          for (int i = 1; i <= 3; i++) {
            InteropContextView.of(ctx).emit(Content.fromText("model", name + i));
          }
          return null;
        });
  }

  /** The message texts of {@code events}, in order. */
  private static ImmutableList<String> textsOf(List<Event> events) {
    return events.stream().map(e -> e.getContent().text()).collect(toImmutableList());
  }

  /** The index of the first of {@code events} that matches {@code test}, or -1. */
  private static int firstIndexOf(List<Event> events, Predicate<Event> test) {
    return IntStream.range(0, events.size())
        .filter(i -> test.test(events.get(i)))
        .findFirst()
        .orElse(-1);
  }

  /** Returns whether {@code event} carries the message {@code text}. */
  private static boolean hasText(Event event, String text) {
    return event.getContent() != null && text.equals(event.getContent().text());
  }

  /**
   * An in-memory session service whose appends wait for {@code beforeStore}, as a remote one does.
   */
  private static final class SlowAppendSessionService extends BaseFutureSessionService {
    private final CompletableFutureSessionService delegate =
        CompletableFutureSessionService.of(new InMemorySessionService());
    private final Function<Event, CompletableFuture<?>> beforeStore;

    SlowAppendSessionService(Function<Event, CompletableFuture<?>> beforeStore) {
      this.beforeStore = beforeStore;
    }

    /** Delays every append by {@code delay}. */
    SlowAppendSessionService(Duration delay) {
      this(event ->
          CompletableFuture.runAsync(
              () -> {},
              CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)));
    }

    @Override
    protected CompletableFuture<Event> appendEventAsync(Session session, Event event) {
      return beforeStore.apply(event).thenCompose(unused -> delegate.appendEvent(session, event));
    }

    @Override
    protected CompletableFuture<Session> createSessionAsync(
        SessionKey key, Map<String, Object> state) {
      return delegate.createSession(key, state);
    }

    @Override
    protected CompletableFuture<Session> getSessionAsync(SessionKey key, GetSessionConfig config) {
      return delegate.getSession(key, config);
    }

    @Override
    protected CompletableFuture<ListSessionsResponse> listSessionsAsync(
        String appName, String userId) {
      return delegate.listSessions(appName, userId);
    }

    @Override
    protected CompletableFuture<Void> deleteSessionAsync(SessionKey key) {
      return delegate.deleteSession(key);
    }

    @Override
    protected CompletableFuture<ListEventsResponse> listEventsAsync(SessionKey key) {
      return delegate.listEvents(key);
    }
  }

  /** The runners {@link #run} created, by app name, so a test can read the session back. */
  private final Map<String, InMemoryRunner> runners = new HashMap<>();

  /** The events of the session that {@link #run} used for {@code appName}. */
  private List<Event> sessionEvents(String appName) {
    InMemoryRunner runner = runners.get(appName);
    SessionKey key = new SessionKey(appName, "user", "session");
    Session session =
        AsyncJavaHelpers.<Session>await(c -> runner.getSessionService().getSession(key, null, c));
    return session.getEvents();
  }

  /** Runs {@code action}, failing instead of hanging if it takes more than 30 seconds. */
  private static <T> T assertTimeoutPreemptively(Callable<T> action) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return executor.submit(action).get(30, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError("the run hung", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Returns whether {@code error} or one of its causes is a {@code type}. */
  private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
    return causeOf(error, type) != null;
  }

  /** Returns {@code error} or its first cause that is a {@code type}, or null if none is. */
  private static <T extends Throwable> T causeOf(Throwable error, Class<T> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
    }
    return null;
  }

  /** Returns whether {@code run} fails because a node rejected its input. */
  private static boolean rejected(Supplier<?> run) {
    try {
      var unused = run.get();
      return false;
    } catch (RuntimeException e) {
      if (isRejection(e)) {
        return true;
      }
      throw e;
    }
  }

  /** Returns whether {@code e} or one of its causes is a node rejecting its input. */
  private static boolean isRejection(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof NodeInputValidationException) {
        return true;
      }
    }
    return false;
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
