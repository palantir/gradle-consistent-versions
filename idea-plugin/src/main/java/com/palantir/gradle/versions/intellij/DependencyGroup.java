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
import java.util.Collections;
import java.util.List;
import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;

@Value.Immutable
public abstract class DependencyGroup {

    @Value.Parameter
    public abstract List<String> parts();

    public static DependencyGroup fromString(String string) {
        return ImmutableDependencyGroup.of(Arrays.asList(string.split("\\.")));
    }

    public final String asUrlString() {
        String url = String.join("/", parts());

        // For some odd reason if you edit the first part of the group you get "IntellijIdeaRulezzz"
        if (url.contains("IntellijIdeaRulezzz")) {
            return "";
        }
        if (!url.isEmpty()) {
            url = url + "/";
        }
        return url;
    }

    public static DependencyGroup groupFromParameters(@NotNull CompletionParameters parameters) {
        PsiElement position = parameters.getPosition();
        PsiElement currentElement = position.getPrevSibling();
        if (currentElement == null) {
            currentElement = position.getParent();
        }

        List<String> newParts = new ArrayList<>();
        while (currentElement != null) {
            if (currentElement.getNode().getElementType() == VersionPropsTypes.GROUP_PART) {
                newParts.add(0, currentElement.getText());
            } else if (currentElement.getNode().getElementType() == VersionPropsTypes.DEPENDENCY_GROUP) {
                newParts.addAll(Arrays.asList(currentElement.getText().split("\\.")));
            }
            currentElement = currentElement.getPrevSibling();
        }
        return ImmutableDependencyGroup.of(newParts);
    }

    public static DependencyGroup createEmpty() {
        return ImmutableDependencyGroup.of(Collections.emptyList());
    }
}
