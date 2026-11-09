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

package com.google.adk.kt.classifiers;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.interop.BaseFutureClassifier;
import com.google.adk.kt.interop.CompletableFutureClassifier;
import com.google.adk.kt.types.Content;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Exercises the classifier types, builders, and future bridges from plain Java. */
@RunWith(JUnit4.class)
public final class ClassifierJavaInteropTest {

  @Test
  public void questionAndRequestConstructorsAndBuilders_roundTripFromJava() {
    // The text constructors take ImmutableMap<String, String> and ImmutableList<String>.
    ImmutableMap<String, String> textCriteria =
        ImmutableMap.of("billing", "Invoices", "technical", "Bugs");
    Choice choiceFromText = new Choice("Route ticket", textCriteria);
    assertThat(choiceFromText.getInstructions())
        .isEqualTo(new ClassifierContent.Text("Route ticket"));
    assertThat(choiceFromText.getCriteria())
        .containsEntry("billing", new ClassifierContent.Text("Invoices"));
    // A null first argument picks the text constructor when the criteria are text.
    assertThat(new Choice(null, textCriteria).getInstructions()).isNull();

    // criterion(label) adds a label with a null description, which Map.of rejects.
    ClassifierContent rubric =
        ClassifierContent.parse("{\"covers\": \"Refunds\", \"not_for\": [\"Shipping\"]}");
    Choice choiceMixed =
        Choice.builder()
            .instructions("Route ticket")
            .criterion("billing", "Invoices")
            .criterion("returns", rubric)
            .criterion("technical")
            .build();
    assertThat(choiceMixed.getCriteria().get("returns")).isSameInstanceAs(rubric);
    assertThat(choiceMixed.getCriteria()).containsEntry("technical", null);
    assertThat(choiceMixed.toBuilder().build()).isEqualTo(choiceMixed);

    Binary binary = new Binary("Is this destructive?");
    assertThat(binary.getCriteria()).isNull();
    assertThat(binary.toBuilder().build()).isEqualTo(binary);
    assertThat(new Binary("Is this destructive?", "Deletes records").getCriteria())
        .isEqualTo(new ClassifierContent.Text("Deletes records"));

    Score score = new Score("Urgency?", ImmutableList.of("low", "high"));
    assertThat(Score.builder().instructions("Urgency?").criteria("low", "high").build())
        .isEqualTo(score);
    assertThat(
            Score.builder()
                .instructions("Urgency?")
                .criteria(
                    ImmutableList.of(
                        new ClassifierContent.Text("low"), new ClassifierContent.Text("high")))
                .build())
        .isEqualTo(score);

    // ImmutableMap<String, Binary> compiles against the Map<String, ? extends Question> param.
    ImmutableMap<String, Binary> questions = ImmutableMap.of("urgent", binary);
    ClassifierRequest textRequest = new ClassifierRequest("double charge", questions);
    assertThat(
            ClassifierRequest.builder()
                .input(new ClassifierContent.Text("double charge"))
                .questions(questions)
                .build())
        .isEqualTo(textRequest);
    assertThat(textRequest.toBuilder().build()).isEqualTo(textRequest);

    Content content = Content.fromText("user", "double charge");
    assertThat(new ClassifierRequest(content, questions))
        .isEqualTo(new ClassifierRequest(new ClassifierInput.Content(content), questions));
  }

  @Test
  public void builderTextOverloads_null_clearsLikeTheContentOverloads() {
    String none = null;
    Binary binary = Binary.builder().instructions("Destructive?").criteria("Deletes data").build();
    Choice labelsOnly =
        Choice.builder()
            .instructions(none)
            .criterion("calm", none)
            .criterion("angry", (ClassifierContent) null)
            .build();

    assertThat(binary.toBuilder().instructions(none).criteria(none).build())
        .isEqualTo(new Binary());
    assertThat(
            binary.toBuilder()
                .instructions((ClassifierContent) null)
                .criteria((ClassifierContent) null)
                .build())
        .isEqualTo(new Binary());
    assertThat(labelsOnly.getInstructions()).isNull();
    assertThat(labelsOnly.getCriteria()).containsExactly("calm", null, "angry", null).inOrder();
    assertThat(Score.builder().instructions(none).criteria("low", "high").build())
        .isEqualTo(new Score(null, ImmutableList.of("low", "high")));
  }

  @Test
  public void classifierContentAndInputOf_fromJava_returnText() {
    assertThat(ClassifierContent.of("ticket")).isEqualTo(new ClassifierContent.Text("ticket"));
    assertThat(ClassifierInput.of("ticket")).isEqualTo(new ClassifierContent.Text("ticket"));
  }

  @Test
  public void classifierContentJson_fromJsonTextOrKotlinxTree_isEqual() {
    ClassifierContent parsed = ClassifierContent.parse("{ \"ticket\": \"refund\" }");
    ClassifierContent.JsonObject fromTree =
        new ClassifierContent.JsonObject(
            new JsonObject(ImmutableMap.of("ticket", JsonElementKt.JsonPrimitive("refund"))));

    assertThat(parsed).isEqualTo(fromTree);
    assertThat(fromTree.getJson().toString()).isEqualTo("{\"ticket\":\"refund\"}");
    assertThat(ClassifierContent.parse("[\"low\", 2]"))
        .isInstanceOf(ClassifierContent.JsonArray.class);
    IllegalArgumentException notJsonContent =
        assertThrows(IllegalArgumentException.class, () -> ClassifierContent.parse("\"secret\""));
    assertThat(notJsonContent).hasMessageThat().doesNotContain("secret");
  }

  @Test
  public void answerConstructorsAndBuilders_roundTripFromJava() {
    ChoiceAnswer choiceAnswer =
        ChoiceAnswer.builder()
            .choice("billing")
            .probabilities(ImmutableMap.of("billing", 0.9, "technical", 0.1))
            .confidence(0.8)
            .build();
    assertThat(choiceAnswer)
        .isEqualTo(
            new ChoiceAnswer("billing", ImmutableMap.of("billing", 0.9, "technical", 0.1), 0.8));
    assertThat(choiceAnswer.toBuilder().build()).isEqualTo(choiceAnswer);

    BinaryAnswer binaryAnswer = new BinaryAnswer(0.75);
    assertThat(binaryAnswer.getConfidence()).isNull();
    assertThat(BinaryAnswer.builder().probability(0.75).build()).isEqualTo(binaryAnswer);

    ScoreAnswer scoreAnswer =
        ScoreAnswer.builder().score(1.0).probabilities(ImmutableMap.of(0, 0.0, 1, 1.0)).build();
    assertThat(scoreAnswer.toBuilder().build()).isEqualTo(scoreAnswer);

    // ImmutableMap<String, BinaryAnswer> compiles against the Map<String, ? extends Answer> param.
    ImmutableMap<String, BinaryAnswer> onlyBinary = ImmutableMap.of("urgent", binaryAnswer);
    ClassifierResponse response =
        ClassifierResponse.builder().answers(onlyBinary).modelVersion("java-jev").build();
    assertThat(response).isEqualTo(new ClassifierResponse(onlyBinary, "java-jev"));
    assertThat(response.toBuilder().build()).isEqualTo(response);

    ClassifierResponse declined =
        new ClassifierResponse(ImmutableMap.of("declined", RefusalAnswer.INSTANCE));
    assertThat(declined.getAnswers().get("declined")).isSameInstanceAs(RefusalAnswer.INSTANCE);
    assertThat(declined.getModelVersion()).isNull();
  }

  @Test
  public void futureBridges_javaImplementsAndCallsClassifier() throws Exception {
    FixedAnswerClassifier impl = new FixedAnswerClassifier();
    ClassifierRequest request =
        new ClassifierRequest(
            "double charge", ImmutableMap.of("urgent", new Binary("Is this destructive?")));

    Classifier delegate;
    ClassifierResponse response;
    try (CompletableFutureClassifier client = CompletableFutureClassifier.of(impl)) {
      delegate = client.asKotlinClassifier();
      response = client.classify(request).get(5, SECONDS);
    }

    assertThat(delegate).isSameInstanceAs(impl);
    assertThat(response)
        .isEqualTo(
            new ClassifierResponse(
                ImmutableMap.of("urgent", new BinaryAnswer(0.75)), "jev-1.13.0"));
  }

  @Test
  public void nullOrMistypedElementsFromJava_rejectedWithIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> new Score("Urgency", Arrays.asList("low", null)));
    Map<String, String> nullKey = new LinkedHashMap<>();
    nullKey.put(null, "desc");
    nullKey.put("ok", "desc");
    assertThrows(IllegalArgumentException.class, () -> new Choice("Route", nullKey));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClassifierRequest("x", Collections.<String, Question>singletonMap("q", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChoiceAnswer("a", Collections.<String, Double>singletonMap("a", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClassifierResponse(Collections.<String, Answer>singletonMap("q", null)));

    IllegalArgumentException mistypedLevel =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Score(rawLevels(new ClassifierContent.Text("low"), "high")));
    assertThat(mistypedLevel)
        .hasMessageThat()
        .isEqualTo("Score criteria levels must be ClassifierContent, got String.");
    IllegalArgumentException mistypedValue =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ScoreAnswer(1.0, rawScoreProbabilities(0, 1)));
    assertThat(mistypedValue)
        .hasMessageThat()
        .isEqualTo("ScoreAnswer probabilities values must be non-null Double, got Int.");
    IllegalArgumentException mistypedKey =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ScoreAnswer(1.0, rawScoreProbabilities("0", 1.0)));
    assertThat(mistypedKey)
        .hasMessageThat()
        .isEqualTo("ScoreAnswer probabilities keys must be Int, got String.");
  }

  // Raw collections bypass Kotlin's declared element types.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static List<ClassifierContent> rawLevels(Object... levels) {
    return (List) Arrays.asList(levels);
  }

  // Raw maps bypass Kotlin's declared key and value types.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Map<Integer, Double> rawScoreProbabilities(Object key, Object value) {
    return (Map) ImmutableMap.of(key, value);
  }

  /** Answers every question with the same {@link BinaryAnswer}. */
  private static final class FixedAnswerClassifier extends BaseFutureClassifier {
    FixedAnswerClassifier() {
      super("java-jev");
    }

    @Override
    protected CompletableFuture<ClassifierResponse> classifyAsync(ClassifierRequest request) {
      ImmutableMap<String, Answer> answers =
          request.getQuestions().keySet().stream()
              .collect(toImmutableMap(name -> name, name -> new BinaryAnswer(0.75)));
      return CompletableFuture.completedFuture(new ClassifierResponse(answers, "jev-1.13.0"));
    }
  }
}
