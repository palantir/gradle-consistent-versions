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

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ReadAction;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.palantir.gradle.versions.intellij.psi.VersionPropsDependencyVersion;
import com.palantir.gradle.versions.intellij.psi.VersionPropsProperty;
import com.palantir.gradle.versions.intellij.psi.VersionPropsTypes;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VersionCompletionContributor extends CompletionContributor {

    private static final Logger log = LoggerFactory.getLogger(VersionCompletionContributor.class);

    VersionCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement(VersionPropsTypes.VERSION),
                new CompletionProvider<>() {
                    @Override
                    public void addCompletions(
                            CompletionParameters parameters, ProcessingContext context, CompletionResultSet resultSet) {

                        VersionPropsDependencyVersion versionElement =
                                ReadAction.compute(() -> (VersionPropsDependencyVersion)
                                        parameters.getPosition().getParent());

                        VersionPropsProperty property = ReadAction.compute(() -> findParentProperty(versionElement));

                        DependencyGroup group = DependencyGroup.fromString(
                                property.getDependencyGroup().getText());
                        DependencyName dependencyPackage =
                                DependencyName.of(property.getDependencyName().getText());

                        List<String> repositories = List.of("https://repo1.maven.org/maven2/");

                        repositories.stream()
                                .map(RepositoryExplorer::new)
                                .flatMap(repositoryExplorer ->
                                        repositoryExplorer.getVersions(group, dependencyPackage).stream())
                                .map(LookupElementBuilder::create)
                                .forEach(resultSet::addElement);
                    }
                });
    }

    private VersionPropsProperty findParentProperty(VersionPropsDependencyVersion versionElement) {
        return versionElement == null ? null : PsiTreeUtil.getParentOfType(versionElement, VersionPropsProperty.class);
    }
}
