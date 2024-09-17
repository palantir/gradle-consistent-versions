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

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.palantir.gradle.versions.intellij.psi.VersionPropsTypes;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VersionPropsAnnotator implements Annotator {
    private static final Logger log = LoggerFactory.getLogger(VersionPropsAnnotator.class);

    @Override
    public final void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {

        List<String> repositories = List.of("https://repo1.maven.org/maven2/");

        Arrays.stream(element.getParent().getChildren())
                .filter(child -> child.getNode() != null)
                .filter(child -> child.getNode().getElementType() == VersionPropsTypes.PROPERTY)
                .filter(child -> element.getText().equals(child.getText()))
                .filter(child -> !child.getText().contains("*"))
                .filter(child -> repositories.stream().noneMatch(repo -> inRepository(child.getText(), repo)))
                .forEach(child -> {
                    holder.newAnnotation(HighlightSeverity.ERROR, "Could not find package in repositories")
                            .range(child.getTextRange())
                            .create();
                });
    }

    private boolean inRepository(@NotNull String line, String repoUrl) {
        DependencyGroup group =
                new DependencyGroup().fromString(line.split("=")[0].split(":")[0]);

        DependencyPackage dependencyPackage = new DependencyPackage(line.split("=")[0].split(":")[1].replace(" ", ""));

        String version = line.split("=")[1].replace(" ", "");

        RepositoryExplorer repositoryExplorer = new RepositoryExplorer(repoUrl);
        List<String> versions = repositoryExplorer.getVersions(group, dependencyPackage);
        return !versions.isEmpty() && versions.contains(version);
    }
}
