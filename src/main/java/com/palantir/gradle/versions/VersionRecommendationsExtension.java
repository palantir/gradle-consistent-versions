/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.versions;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;

public class VersionRecommendationsExtension {
    private static final ImmutableSet<String> DEFAULT_EXCLUDED_CONFIGURATIONS = ImmutableSet.of(
            "archives",
            "nebulaRecommenderBom",
            "provided",
            "versionManagement",
            "resolutionRules",
            "bootArchives",
            "webapp");
    public static final String EXTENSION = "versionRecommendations";

    private final SetProperty<String> excludeConfigurations;

    @Inject
    public VersionRecommendationsExtension(Project project) {
        excludeConfigurations = project.getObjects().setProperty(String.class).empty();
        excludeConfigurations.addAll(DEFAULT_EXCLUDED_CONFIGURATIONS);
    }

    @Inject
    public VersionRecommendationsExtension(Project project, VersionRecommendationsExtension rootExtension) {
        excludeConfigurations = project.getObjects().setProperty(String.class);
        excludeConfigurations.set(rootExtension.getExcludeConfigurations());
    }

    public final void excludeConfigurations(String... configurations) {
        excludeConfigurations.addAll(configurations);
    }

    public final void setExcludeConfigurations(String... configurations) {
        excludeConfigurations.set(Lists.newArrayList(configurations));
    }

    final Provider<Set<String>> getExcludeConfigurations() {
        return excludeConfigurations;
    }

    final boolean shouldExcludeConfiguration(String configuration) {
        return excludeConfigurations.get().contains(configuration);
    }
}
