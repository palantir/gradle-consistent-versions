/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.gradle.versions.intellij;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;
import org.junit.jupiter.api.Test;

public class VersionPropsCodeInsightTest extends LightJavaCodeInsightFixtureTestCase {

    @Test
    public void test_version_completion() throws Exception {
        setUp();
        // The file name is required for context but does not need to exist on the filesystem
        myFixture.configureByText("DummyPropsFile.props", "com.palantir.baseline:baseline-error-prone = <caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> lookupElementStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupElementStrings);
        assertContainsElements(lookupElementStrings, "0.66.0", "2.40.2");
        tearDown();
    }

    @Test
    public void test_group_completion() throws Exception {
        setUp();
        // The file name is required for context but does not need to exist on the filesystem
        myFixture.configureByText("DummyPropsFile.props", "com.palantir.baseline.<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> lookupElementStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupElementStrings);
        assertContainsElements(lookupElementStrings, "baseline-error-prone", "baseline-null-away");
        tearDown();
    }

    @Test
    public void test_package_completion() throws Exception {
        setUp();
        // The file name is required for context but does not need to exist on the filesystem
        myFixture.configureByText("DummyPropsFile.props", "com.palantir.baseline:<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> lookupElementStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupElementStrings);
        assertContainsElements(lookupElementStrings, "baseline-error-prone", "baseline-null-away");
        tearDown();
    }
}
