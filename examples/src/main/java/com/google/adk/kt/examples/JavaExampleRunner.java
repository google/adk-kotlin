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

package com.google.adk.kt.examples;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.runners.ReplRunner;

/**
 * Runs any Java example agent in a REPL, given the fully qualified name of a class exposing a
 * {@code public static rootAgent} field.
 */
public final class JavaExampleRunner {

  public static void main(String[] args) throws ReflectiveOperationException {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "Usage: JavaExampleRunner <fully.qualified.AgentClass>, e.g."
              + " com.google.adk.kt.examples.hello.HelloAgentJava");
    }
    BaseAgent agent = (BaseAgent) Class.forName(args[0]).getField("rootAgent").get(null);
    new ReplRunner(agent).start();
  }

  private JavaExampleRunner() {}
}
