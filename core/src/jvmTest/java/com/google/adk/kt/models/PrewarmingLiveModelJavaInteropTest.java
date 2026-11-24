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

package com.google.adk.kt.models;

import static org.junit.Assert.assertThrows;

import com.google.adk.kt.interop.BasePublisherModel;
import kotlinx.coroutines.Dispatchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.reactivestreams.Publisher;

/** Builds a {@link PrewarmingLiveModel} from plain Java, where only its builder is available. */
@RunWith(JUnit4.class)
public final class PrewarmingLiveModelJavaInteropTest {

  /** A model that is never called: building and closing the pool does not open any connections. */
  private static final class UnusedModel extends BasePublisherModel {
    UnusedModel() {
      super("unused-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void builder_everySetter_buildsAPool() {
    PrewarmingLiveModel prewarming =
        PrewarmingLiveModel.builder()
            .model(new UnusedModel())
            .spares(2)
            .maxSetups(3)
            .keepWarmForMillis(5_000)
            .minLifetimeForReplacementMillis(6_000)
            .maxSpareAgeMillis(8_000)
            .updateCheckWindowMillis(7_000)
            .context(Dispatchers.getDefault())
            .build();

    prewarming.close();
  }

  @Test
  public void builder_nullDurations_buildAPool() {
    PrewarmingLiveModel prewarming =
        PrewarmingLiveModel.builder()
            .model(new UnusedModel())
            .keepWarmForMillis(null)
            .minLifetimeForReplacementMillis(null)
            .maxSpareAgeMillis(null)
            .updateCheckWindowMillis(null)
            .build();

    prewarming.close();
  }

  @Test
  public void builder_noModel_throwsIllegalStateException() {
    assertThrows(IllegalStateException.class, () -> PrewarmingLiveModel.builder().build());
  }
}
