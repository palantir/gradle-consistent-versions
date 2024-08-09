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

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;

public class VersionPropsAnnotatorTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/testData";
    }

    public final void testAnnotator() {
        myFixture.configureByText("VersionProps.properties", "group:package=1.0.0");

        // Trigger highlighting
        List<HighlightInfo> highlightInfos = myFixture.doHighlighting();

        // Collect error messages
        List<String> errors = highlightInfos.stream()
                .filter(info -> info.getSeverity().equals(HighlightSeverity.ERROR))
                .map(HighlightInfo::getDescription)
                .toList();

        // Assert no errors found
        assertTrue("Errors found: " + errors, errors.isEmpty());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            Disposer.dispose(myFixture.getTestRootDisposable());
        } finally {
            super.tearDown();
        }
    }
}
