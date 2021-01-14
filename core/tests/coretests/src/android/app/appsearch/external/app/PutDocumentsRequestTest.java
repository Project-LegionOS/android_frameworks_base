/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.appsearch;


import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.Set;

public class PutDocumentsRequestTest {

    @Test
    public void addGenericDocument_byCollection() {
        Set<AppSearchEmail> emails =
                ImmutableSet.of(
                        new AppSearchEmail.Builder("test1").build(),
                        new AppSearchEmail.Builder("test2").build());
        PutDocumentsRequest request =
                new PutDocumentsRequest.Builder().addGenericDocument(emails).build();

        assertThat(request.getDocuments().get(0).getUri()).isEqualTo("test1");
        assertThat(request.getDocuments().get(1).getUri()).isEqualTo("test2");
    }
}
