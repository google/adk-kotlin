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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.interop.BaseFutureClassifier;
import com.google.adk.kt.interop.CompletableFutureClassifier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Exercises the classifier types, builders and future bridges from plain Java ({@code javac}). */
@RunWith(JUnit4.class)
public final class ClassifierJavaInteropTest {

  @Test
  public void questionConstructorsAndBuilders_roundTripFromJava() {
    // ImmutableMap<String, String> and ImmutableList<String> compile against the Map<String, ?>
    // and List<?> params.
    ImmutableMap<String, String> typedCriteria =
        ImmutableMap.of("billing", "Invoices", "technical", "Bugs");
    Choice choiceFromTypedMap = new Choice(typedCriteria);
    assertNull(choiceFromTypedMap.getInstructions());
    assertEquals("Invoices", choiceFromTypedMap.getCriteria().get("billing"));

    // Choice.Builder.criterion supports null descriptions without Map.of throwing NPE.
    Choice choiceMixed =
        Choice.builder()
            .instructions("Route ticket")
            .criterion("billing", "Invoices")
            .criterion("technical")
            .build();
    assertEquals(choiceMixed, choiceMixed.toBuilder().build());

    Binary binary = new Binary("Is this destructive?");
    assertNull(binary.getCriteria());
    assertEquals(binary, binary.toBuilder().build());

    ImmutableList<String> typedLevels = ImmutableList.of("low", "high");
    Score score = new Score("Urgency?", typedLevels);
    assertEquals(score, Score.builder().instructions("Urgency?").criteria(typedLevels).build());
  }

  @Test
  public void answerConstructorsAndBuilders_roundTripFromJava() {
    ChoiceAnswer choiceAnswer =
        ChoiceAnswer.builder()
            .choice("billing")
            .probabilities(ImmutableMap.of("billing", 0.9, "technical", 0.1))
            .confidence(0.8)
            .build();
    assertEquals(
        new ChoiceAnswer("billing", ImmutableMap.of("billing", 0.9, "technical", 0.1), 0.8),
        choiceAnswer);
    assertEquals(choiceAnswer, choiceAnswer.toBuilder().build());

    BinaryAnswer binaryAnswer = new BinaryAnswer(0.75);
    assertNull(binaryAnswer.getConfidence());
    assertEquals(binaryAnswer, BinaryAnswer.builder().probability(0.75).build());

    ScoreAnswer scoreAnswer =
        ScoreAnswer.builder().score(1.0).probabilities(ImmutableMap.of(0, 0.0, 1, 1.0)).build();
    assertEquals(scoreAnswer, scoreAnswer.toBuilder().build());

    // ImmutableMap<String, BinaryAnswer> compiles against the Map<String, ? extends Answer> param.
    ImmutableMap<String, BinaryAnswer> onlyBinary = ImmutableMap.of("urgent", binaryAnswer);
    ClassifierResponse response =
        ClassifierResponse.builder().answers(onlyBinary).modelVersion("java-jev").build();
    assertEquals(new ClassifierResponse(onlyBinary, "java-jev"), response);
    assertEquals(response, response.toBuilder().build());

    ClassifierResponse declined =
        new ClassifierResponse(ImmutableMap.of("declined", RefusalAnswer.INSTANCE));
    assertSame(RefusalAnswer.INSTANCE, declined.getAnswers().get("declined"));
    assertNull(declined.getModelVersion());
  }

  @Test
  public void futureBridges_javaImplementsAndCallsClassifier() throws Exception {
    FixedAnswerClassifier impl = new FixedAnswerClassifier();
    try (CompletableFutureClassifier client = CompletableFutureClassifier.of(impl)) {
      assertSame(impl, client.asKotlinClassifier());

      // ImmutableMap<String, Binary> compiles against the Map<String, ? extends Question> param.
      ImmutableMap<String, Binary> onlyBinary =
          ImmutableMap.of("urgent", new Binary("Is this destructive?"));
      assertEquals(
          new ClassifierResponse(ImmutableMap.of("urgent", new BinaryAnswer(0.75)), "jev-1.13.0"),
          client.classify("double charge", onlyBinary).get(5, SECONDS));
    }
  }

  @Test
  public void nullOrMistypedElementsFromJava_rejectedWithIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> new Score("Urgency", Arrays.asList("low", null)));
    Map<String, String> nullKey = new LinkedHashMap<>();
    nullKey.put(null, "desc");
    nullKey.put("ok", "desc");
    assertThrows(IllegalArgumentException.class, () -> new Choice(nullKey));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChoiceAnswer("a", Collections.<String, Double>singletonMap("a", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClassifierResponse(Collections.<String, Answer>singletonMap("q", null)));

    // Raw maps bypass Kotlin's declared key and value types.
    @SuppressWarnings({"rawtypes", "unchecked"})
    Map<Integer, Double> integerValued = (Map) ImmutableMap.of(0, 1);
    IllegalArgumentException mistypedValue =
        assertThrows(IllegalArgumentException.class, () -> new ScoreAnswer(1.0, integerValued));
    assertEquals(
        "ScoreAnswer probabilities values must be non-null Double, got Int.",
        mistypedValue.getMessage());
    // Raw maps bypass Kotlin's declared key and value types.
    @SuppressWarnings({"rawtypes", "unchecked"})
    Map<Integer, Double> stringKeyed = (Map) ImmutableMap.of("0", 1.0);
    IllegalArgumentException mistypedKey =
        assertThrows(IllegalArgumentException.class, () -> new ScoreAnswer(1.0, stringKeyed));
    assertEquals(
        "ScoreAnswer probabilities keys must be Int, got String.", mistypedKey.getMessage());
  }

  /** Answers every question with the same {@link BinaryAnswer}. */
  private static final class FixedAnswerClassifier extends BaseFutureClassifier {
    FixedAnswerClassifier() {
      super("java-jev");
    }

    @Override
    protected CompletableFuture<ClassifierResponse> classifyAsync(
        Object input, Map<String, Question> questions) {
      Map<String, Answer> answers = new LinkedHashMap<>();
      for (String name : questions.keySet()) {
        answers.put(name, new BinaryAnswer(0.75));
      }
      return CompletableFuture.completedFuture(new ClassifierResponse(answers, "jev-1.13.0"));
    }
  }
}
