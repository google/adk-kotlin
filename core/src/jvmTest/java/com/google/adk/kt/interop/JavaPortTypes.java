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

package com.google.adk.kt.interop;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.util.StdConverter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Java port types whose schemas {@code JavaPortSchemasTest} checks. */
final class JavaPortTypes {

  /** A type-use {@code @Nullable}, as JSpecify's is; the inference matches it by simple name. */
  @Target(ElementType.TYPE_USE)
  @Retention(RetentionPolicy.RUNTIME)
  @interface Nullable {}

  record Ticket(String id, int priority, double score, List<String> tags) {}

  /**
   * A profile whose {@code email} has Jackson's built-in reference type, as the port's mapper
   * registers no {@code Optional} support.
   */
  record Profile(
      String name,
      @JsonAlias("nick") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nickname,
      AtomicReference<String> email,
      long visits) {}

  /** A POJO that writes its ticket's keys among its own. */
  public static final class Contact {
    public String name;
    @JsonUnwrapped public Ticket ticket;
  }

  /** A POJO that writes its ticket's type id under a key of its own. */
  public static final class Shipment {
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
        property = "kind")
    public Ticket ticket;
  }

  record Order(String id, @Nullable String note, Object extra) {}

  /** A POJO that Jackson also creates from a string, through its delegating factory. */
  public static final class Label {
    public String text;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Label of(String text) {
      Label label = new Label();
      label.text = text;
      return label;
    }
  }

  /** A record whose nullable id is marked required. */
  record Strict(@JsonProperty(required = true) @Nullable String id) {}

  /** A POJO whose field no creator reads, so an input never requires it. */
  public static final class Plain {
    public String name = "n";
  }

  /** A class whose private field alone marks {@code text} nullable; its getter does not. */
  public static final class Note {
    private final @Nullable String text;

    @JsonCreator
    Note(@JsonProperty("text") String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }
  }

  /** A POJO that Jackson also creates from a number, through its {@code BigDecimal} constructor. */
  public static final class Money {
    public BigDecimal amount;

    public Money() {}

    public Money(BigDecimal amount) {
      this.amount = amount;
    }
  }

  /** A POJO whose class-level NON_DEFAULT omits any value equal to its initializer. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static final class Defaults {
    public Priority priority = Priority.LOW;
    public String note = "n";
    public @Nullable String extra = "x";
  }

  record Unit(String id) {}

  /** A record whose class-level NON_DEFAULT omits a zero count but still writes a nested record. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  record Packed(int count, Unit unit) {}

  /** A POJO whose getter alone marks {@code note} nullable. */
  public static final class Memo {
    private String note;

    public @Nullable String getNote() {
      return note;
    }

    public void setNote(String note) {
      this.note = note;
    }
  }

  /** A record that writes a derived total it does not read back. */
  record Invoice(String id, int quantity) {
    @JsonProperty
    public int getTotal() {
      return quantity * 10;
    }
  }

  /** A POJO that Jackson creates from any JSON object through its delegating factory. */
  public static final class Tags {
    public List<String> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Tags of(Map<String, String> byName) {
      Tags tags = new Tags();
      tags.values = List.copyOf(byName.values());
      return tags;
    }
  }

  /** {@link Tags} that Jackson also creates from a string. */
  public static final class Keywords {
    public List<String> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    Keywords(String text) {
      values = List.of(text);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Keywords of(Map<String, String> byName) {
      return new Keywords(String.join(" ", byName.values()));
    }
  }

  /** {@link Tags} that Jackson also creates from an array, through its list creator. */
  public static final class Words {
    public List<String> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Words of(Map<String, String> byName) {
      return ofList(List.copyOf(byName.values()));
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Words ofList(List<String> values) {
      Words words = new Words();
      words.values = values;
      return words;
    }
  }

  /** A POJO that Jackson reads through its delegating factory, never its properties creator. */
  public static final class Entry {
    public String key;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    Entry(@JsonProperty("key") String key) {
      this.key = key;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Entry of(Map<String, String> byKey) {
      Entry entry = new Entry(null);
      entry.key = byKey.keySet().iterator().next();
      return entry;
    }
  }

  /** A POJO whose delegating factory reads its map through a deserializer of its own. */
  public static final class Parsed {
    public Map<String, Object> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Parsed of(@JsonDeserialize(using = TextToMap.class) Map<String, Object> values) {
      Parsed parsed = new Parsed();
      parsed.values = values;
      return parsed;
    }
  }

  /** Reads a string such as {@code "k=v"} as a map of one entry. */
  public static final class TextToMap extends StdDeserializer<Map<String, Object>> {
    public TextToMap() {
      super(Map.class);
    }

    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      String[] parts = parser.getText().split("=", 2);
      return Map.of(parts[0], parts[1]);
    }
  }

  /** A record with a property read through a delegating creator. */
  record Holder(Tags tags) {}

  /** A POJO that Jackson creates from a number through its delegating constructor. */
  public static final class Serial {
    public BigInteger value;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    Serial(BigInteger value) {
      this.value = value;
    }
  }

  /** A POJO that Jackson creates from a reference to its own type, so it contains itself. */
  public static final class Chain {
    public AtomicReference<Chain> next;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Chain of(AtomicReference<Chain> next) {
      Chain chain = new Chain();
      chain.next = next;
      return chain;
    }
  }

  enum Priority {
    LOW,
    @JsonProperty("urgent")
    HIGH
  }

  record Task(Priority priority) {}

  record Tree(String label, List<Tree> children) {}

  /** An AutoValue-style type, which Jackson creates through a factory method. */
  abstract static class Point {
    @JsonProperty("x")
    abstract int x();

    @JsonCreator
    static Point create(@JsonProperty("x") int x) {
      return new Point() {
        @Override
        int x() {
          return x;
        }
      };
    }
  }

  record Labels(
      @JsonProperty(required = true) @JsonAlias("title") String name,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> tags) {}

  /** A record whose JSON form may omit all but two keys. */
  record Sparse(
      @JsonInclude(JsonInclude.Include.NON_EMPTY) String text,
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) int count,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) AtomicReference<String> model,
      @JsonInclude(JsonInclude.Include.NON_NULL) AtomicReference<String> label,
      @JsonSerialize(converter = NumberToText.class) int code,
      String plain) {}

  /** A record whose class-level inclusion omits only the keys that can be null. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Counted(int count, String label, @Nullable String note, AtomicReference<String> tag) {}

  /** A record that reads a password it never writes, and writes a role it never reads. */
  record Login(
      String user,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
      @JsonProperty(access = JsonProperty.Access.READ_ONLY) String role) {}

  /** A record whose properties have no fixed JSON type on output, input or both. */
  record Irregular(
      @JsonSerialize(using = ToStringSerializer.class) long id,
      @JsonDeserialize(using = StringDeserializer.class) String parsed,
      @JsonSerialize(contentUsing = ToStringSerializer.class) List<Integer> codes,
      @JsonDeserialize(contentUsing = StringDeserializer.class) List<String> notes,
      @JsonDeserialize(converter = TextToNumber.class) int read,
      @JsonFormat(shape = JsonFormat.Shape.STRING) int count,
      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME) Ticket typed,
      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME) List<Ticket> typedTickets,
      @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class)
          @JsonIdentityReference(alwaysAsId = true)
          Ticket byId,
      List<Pet> pets,
      Object raw) {}

  /** A record that also writes the entries of a map as keys of its own. */
  record Tagged(String name) {
    @JsonAnyGetter
    Map<String, Object> extra() {
      return Map.of("k", 1);
    }
  }

  /** A class whose any-setter takes keys it does not declare. */
  public static final class Catchall {
    public String id;

    @JsonAnySetter
    void put(String key, Object value) {}
  }

  @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class)
  record Ref(String label) {}

  /** A polymorphic base type with a property of its own. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  abstract static class Pet {
    public String name;
  }

  /** An enum that Jackson writes as a number. */
  enum Level {
    LOW(1),
    HIGH(2);

    private final int code;

    Level(int code) {
      this.code = code;
    }

    @JsonValue
    int code() {
      return code;
    }
  }

  /** A POJO of scalars Jackson writes as text or a boolean, and a map. */
  public static final class Scalars {
    public byte[] bytes;
    public char[] chars;
    public char letter;
    public boolean flag;
    public Map<String, Integer> counts;
  }

  /** Holds a declaration annotation named like the {@code @Nullable} of other packages. */
  static final class Declared {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    private Declared() {}
  }

  record Hinted(String text, @Declared.Nullable String hint) {}

  /** A record that reads b but never writes it. */
  @JsonIgnoreProperties(value = "b", allowSetters = true)
  record Hidden(String a, String b) {}

  /** A record whose inclusions may omit keys, though none of its values can be empty. */
  record Lenient(
      @JsonInclude(JsonInclude.Include.NON_EMPTY) int count,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Unit unit) {}

  static final class NumberToText extends StdConverter<Integer, String> {
    @Override
    public String convert(Integer value) {
      return value.toString();
    }
  }

  static final class TextToNumber extends StdConverter<String, Integer> {
    @Override
    public Integer convert(String value) {
      return Integer.valueOf(value);
    }
  }

  @JsonFormat(shape = JsonFormat.Shape.ARRAY)
  record Coords(int x, int y) {}

  record Box<T>(T left, T right) {}

  /** A class that declares {@code @JsonAnySetter} on a {@code @JsonCreator} parameter. */
  public static final class CreatorAnySetter {
    public final String id;
    public final Map<String, Object> extra;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public CreatorAnySetter(
        @JsonProperty("id") String id, @JsonAnySetter Map<String, Object> extra) {
      this.id = id;
      this.extra = extra;
    }
  }

  /** A concrete class with a {@code @JsonCreator} static factory and matching public fields. */
  public static final class FactoryWithField {
    public String id;
    public int count;

    private FactoryWithField(String id, int count) {
      this.id = id;
      this.count = count;
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static FactoryWithField create(
        @JsonProperty(value = "id", required = true) String id, @JsonProperty("count") int count) {
      return new FactoryWithField(id, count);
    }
  }

  /** An enum that writes text names but reads an integer through a {@code @JsonCreator}. */
  public enum IntCreatorEnum {
    ONE,
    TWO;

    @JsonCreator
    public static IntCreatorEnum fromCode(int code) {
      return code == 1 ? ONE : TWO;
    }
  }

  /** An enum that writes text names but reads through a custom deserializer. */
  @JsonDeserialize(using = CustomDeserEnumReader.class)
  public enum CustomDeserEnum {
    ALPHA,
    BETA
  }

  static final class CustomDeserEnumReader extends StdDeserializer<CustomDeserEnum> {
    CustomDeserEnumReader() {
      super(CustomDeserEnum.class);
    }

    @Override
    public CustomDeserEnum deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      return parser.getIntValue() == 1 ? CustomDeserEnum.ALPHA : CustomDeserEnum.BETA;
    }
  }

  /**
   * A POJO whose {@code @Nullable} sits only on a {@code WRITE_ONLY} field (invisible on the
   * serialization view, visible only on the deserialization view).
   */
  public static final class WriteOnlyNullable {
    public final String id;

    @SuppressWarnings("unused")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private final @Nullable String secret;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public WriteOnlyNullable(@JsonProperty("id") String id, @JsonProperty("secret") String secret) {
      this.id = id;
      this.secret = secret;
    }
  }

  /** A class with a non-bean serializer and a properties creator. */
  @JsonSerialize(using = ToStringSerializer.class)
  public static final class CustomSerializedCreator {
    public final String id;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public CustomSerializedCreator(@JsonProperty("id") String id) {
      this.id = id;
    }
  }

  /** A record whose reference-type field carries {@code @JsonTypeInfo} on its content. */
  record TypedReference(@JsonTypeInfo(use = JsonTypeInfo.Id.NAME) AtomicReference<Ticket> ticket) {}

  /** A class with type-use {@code @Nullable} on list, array, nested, and reference elements. */
  public static final class NullableElements {
    public final List<@Nullable String> list;
    public final @Nullable String[] array;
    public final String @Nullable [] nullableArray;
    public final List<List<@Nullable String>> grid;
    public final AtomicReference<List<@Nullable String>> refList;
    public final List<AtomicReference<List<@Nullable String>>> refLists;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public NullableElements(
        @JsonProperty("list") List<@Nullable String> list,
        @JsonProperty("array") @Nullable String[] array,
        @JsonProperty("nullableArray") String @Nullable [] nullableArray,
        @JsonProperty("grid") List<List<@Nullable String>> grid,
        @JsonProperty("refList") AtomicReference<List<@Nullable String>> refList,
        @JsonProperty("refLists") List<AtomicReference<List<@Nullable String>>> refLists) {
      this.list = list;
      this.array = array;
      this.nullableArray = nullableArray;
      this.grid = grid;
      this.refList = refList;
      this.refLists = refLists;
    }
  }

  /** Records whose list elements convert via {@code contentConverter} on write or read. */
  record ConvertedCodes(
      @JsonSerialize(contentConverter = NumberToText.class) List<Integer> codes) {}

  record ConvertedReads(
      @JsonDeserialize(contentConverter = TextToNumber.class) List<Integer> nums) {}

  /** An item whose subclass adds a key. */
  public static class BaseItem {
    public String name;
  }

  public static final class SubItem extends BaseItem {
    public String extra;
  }

  /** A record that writes its items as their supertype, without the {@code extra} key. */
  record WrittenAsBase(
      @JsonSerialize(as = BaseItem.class) SubItem item,
      @JsonSerialize(contentAs = BaseItem.class) List<SubItem> items) {}

  /** A record that writes its items as a subclass, whose {@code extra} key it does not read. */
  record WrittenAsSub(
      @JsonSerialize(as = SubItem.class) BaseItem item,
      @JsonSerialize(contentAs = SubItem.class) List<BaseItem> items) {}

  /** A POJO with type-use {@code @Nullable} only on its {@code @JsonCreator} parameter. */
  public static final class ParamNullable {
    private final String text;

    @JsonCreator
    public ParamNullable(@JsonProperty("text") @Nullable String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }
  }

  /** A POJO with type-use {@code @Nullable} only on its setter parameter. */
  public static final class SetterNullable {
    private String text;

    public String getText() {
      return text;
    }

    public void setText(@Nullable String text) {
      this.text = text;
    }
  }

  /** A POJO whose unwrapped field is {@code WRITE_ONLY} with no setter. */
  public static final class WriteOnlyUnwrapped {
    public String name;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @JsonUnwrapped
    public Ticket ticket;
  }

  /** A POJO whose external-property polymorphic field is {@code WRITE_ONLY} with no setter. */
  public static final class WriteOnlyExternalType {
    public String name;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
        property = "kind")
    public Ticket ticket;
  }

  /** A record with {@code NON_DEFAULT} on a {@link Date}, which Jackson omits at epoch zero. */
  record NonDefaultDate(@JsonInclude(JsonInclude.Include.NON_DEFAULT) Date createdAt, String id) {}

  /** An interface Jackson reads as {@link NullableImplViewImpl}, which has keys of its own. */
  @JsonDeserialize(as = NullableImplViewImpl.class)
  public interface NullableImplView {
    String getName();
  }

  public static final class NullableImplViewImpl implements NullableImplView {
    private final String name;
    private final @Nullable String note;

    @JsonCreator
    public NullableImplViewImpl(
        @JsonProperty("name") String name, @JsonProperty("note") String note) {
      this.name = name;
      this.note = note;
    }

    @Override
    public String getName() {
      return name;
    }

    public @Nullable String getNote() {
      return note;
    }
  }

  /** A class Jackson reads through its builder. */
  @JsonDeserialize(builder = BuiltProfile.Builder.class)
  public static final class BuiltProfile {
    private final String name;

    private BuiltProfile(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public static final class Builder {
      private String name;

      public Builder withName(String name) {
        this.name = name;
        return this;
      }

      public BuiltProfile build() {
        return new BuiltProfile(name);
      }
    }
  }

  private JavaPortTypes() {}
}
