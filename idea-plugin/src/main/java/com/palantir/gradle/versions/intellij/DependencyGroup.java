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

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.psi.PsiElement;
import com.palantir.gradle.versions.intellij.psi.VersionPropsTypes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class DependencyGroup {
    private final List<String> parts = new ArrayList<>();

    public final boolean isEmpty() {
        return parts.isEmpty();
    }

    private void addPart(String part) {
        parts.add(0, part); // Add to the beginning to maintain order
    }

    private void addFromString(String string) {
        parts.addAll(Arrays.asList(string.split("\\."))); // Escape the dot
    }

    public final DependencyGroup fromString(String string) {
        parts.clear();
        addFromString(string);
        return this;
    }

    public final String asUrlString() {
        String url = String.join("/", parts);

        // for som odd reason if you edit the first part of the group you get "IntellijIdeaRulezzz"
        if (url.contains("IntellijIdeaRulezzz")) {
            return "";
        }
        if (!url.isEmpty()) {
            url = url + "/";
        }
        return url;
    }

    public final DependencyGroup groupFromParameters(@NotNull CompletionParameters parameters) {
        parts.clear();
        PsiElement position = parameters.getPosition();

        PsiElement currentElement = position.getPrevSibling();
        if (currentElement == null) {
            currentElement = position.getParent();
        }

        while (currentElement != null) {
            if (currentElement.getNode().getElementType() == VersionPropsTypes.GROUP_PART) {
                addPart(currentElement.getText());
            } else if (currentElement.getNode().getElementType() == VersionPropsTypes.DEPENDENCY_GROUP) {
                addFromString(currentElement.getText());
            }
            currentElement = currentElement.getPrevSibling();
        }
        return this;
    }
}
