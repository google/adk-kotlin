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

package com.google.adk.kt.auth;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Builds auth types from Java through the overloads {@code @JvmOverloads} generates. */
@RunWith(JUnit4.class)
public final class AuthJavaInteropTest {

  @Test
  public void apiKeyScheme_nameAndLocationOnly_hasNoDescription() {
    // Act
    AuthScheme.ApiKey scheme = new AuthScheme.ApiKey("X-Key", AuthScheme.ApiKey.Location.HEADER);

    // Assert
    assertThat(scheme)
        .isEqualTo(new AuthScheme.ApiKey("X-Key", AuthScheme.ApiKey.Location.HEADER, null));
  }

  @Test
  public void authConfig_schemeOnly_matchesTheBuilderDefaults() {
    // Arrange
    AuthScheme.ApiKey scheme = new AuthScheme.ApiKey("X-Key", AuthScheme.ApiKey.Location.HEADER);

    // Act
    AuthConfig config = new AuthConfig(scheme);

    // Assert
    assertThat(config).isEqualTo(AuthConfig.builder().authScheme(scheme).build());
  }

  @Test
  public void oauth2Credential_variantAndClientIdOnly_leavesTheRestUnset() {
    // Act
    AuthCredential.OAuth2 credential =
        new AuthCredential.OAuth2(AuthCredential.OAuth2.Variant.OAUTH2, "cid");

    // Assert
    assertThat(credential).isEqualTo(AuthCredential.OAuth2.builder().clientId("cid").build());
  }
}
