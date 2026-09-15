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

package com.google.adk.kt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvUtilsTest {

  @Test
  fun isValueEnabled_trueInAnyCase_isEnabled() {
    assertTrue(EnvUtils.isValueEnabled("true"))
    assertTrue(EnvUtils.isValueEnabled("TRUE"))
    assertTrue(EnvUtils.isValueEnabled("True"))
  }

  @Test
  fun isValueEnabled_one_isEnabled() {
    assertTrue(EnvUtils.isValueEnabled("1"))
  }

  @Test
  fun isValueEnabled_otherAffirmativeSpellings_areNotEnabled() {
    // The SDK reads flags by one rule everywhere, so nothing but "true" and "1" turns one on.
    assertFalse(EnvUtils.isValueEnabled("yes"))
    assertFalse(EnvUtils.isValueEnabled("on"))
    assertFalse(EnvUtils.isValueEnabled("2"))
    assertFalse(EnvUtils.isValueEnabled("TRUE!"))
  }

  @Test
  fun isValueEnabled_paddedTrue_isNotEnabled() {
    assertFalse(EnvUtils.isValueEnabled(" true "))
  }

  @Test
  fun isValueEnabled_falseEmptyOrUnset_isNotEnabled() {
    assertFalse(EnvUtils.isValueEnabled("false"))
    assertFalse(EnvUtils.isValueEnabled("0"))
    assertFalse(EnvUtils.isValueEnabled(""))
    assertFalse(EnvUtils.isValueEnabled(null))
  }

  @Test
  fun isEnterpriseModeEnabled_currentVariableSet_decidesOnItsOwn() {
    assertTrue(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = "true", deprecatedValue = null))
    assertTrue(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = "1", deprecatedValue = "false"))
  }

  @Test
  fun isEnterpriseModeEnabled_currentVariableSaysOff_overridesTheDeprecatedOne() {
    // A deployment that moved to the current variable and turned it off must not be dragged back
    // to the enterprise backend by a stale deprecated variable still in its environment.
    assertFalse(
      EnvUtils.isEnterpriseModeEnabled(enterpriseValue = "false", deprecatedValue = "true")
    )
  }

  @Test
  fun isEnterpriseModeEnabled_currentVariableBlank_stillDecides() {
    // Set-but-blank is still set, so the deprecated variable is not consulted.
    assertFalse(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = "", deprecatedValue = "true"))
  }

  @Test
  fun isEnterpriseModeEnabled_onlyDeprecatedVariableSet_isHonoured() {
    assertTrue(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = null, deprecatedValue = "true"))
    assertFalse(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = null, deprecatedValue = "false"))
  }

  @Test
  fun isEnterpriseModeEnabled_neitherVariableSet_isNotEnabled() {
    assertFalse(EnvUtils.isEnterpriseModeEnabled(enterpriseValue = null, deprecatedValue = null))
  }
}
