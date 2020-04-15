/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

import netflix.nebula.dependency.recommender.RecommendationStrategies;
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

public final class NebulaUtils {

    static void verifyRecommendationStrategy(Project project) {
        RecommendationProviderContainer container =
                project.getExtensions().findByType(RecommendationProviderContainer.class);
        if (container.getStrategy() == RecommendationStrategies.OverrideTransitives) {
            throw new GradleException("Must not use strategy OverrideTransitives for "
                    + project
                    + ". Use this instead: dependencyRecommendations { strategy ConflictResolved }");
        }
    }

    private NebulaUtils() {}
}
