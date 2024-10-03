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

package com.palantir.gradle.versions;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.StartParameter;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VersionsPropsIdeaPlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(VersionsPropsIdeaPlugin.class);

    @Override
    public void apply(Project project) {

        if (!Boolean.getBoolean("idea.active")) {
            return;
        }

        TaskProvider<GenerateMavenRepositoriesTask> writeMavenRepositories = project.getTasks()
                .register("writeMavenRepositories", GenerateMavenRepositoriesTask.class, task -> {
                    Provider<Set<String>> repositoryProvider = project.provider(() -> project.getRepositories().stream()
                            .filter(repo -> repo instanceof MavenArtifactRepository)
                            .map(repo ->
                                    ((MavenArtifactRepository) repo).getUrl().toString())
                            .map(url -> url.endsWith("/") ? url : url + "/")
                            .collect(Collectors.toSet()));
                    task.getMavenRepositories().set(repositoryProvider);
                });

        StartParameter startParameter = project.getGradle().getStartParameter();
        List<String> taskNames = startParameter.getTaskNames();
        taskNames.add(String.format(":%s", writeMavenRepositories.getName()));
        startParameter.setTaskNames(taskNames);
    }
}
