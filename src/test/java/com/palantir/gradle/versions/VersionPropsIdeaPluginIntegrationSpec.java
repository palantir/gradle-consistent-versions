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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.execution.TaskOutcome;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class VersionPropsIdeaPluginIntegrationSpec {

    @BeforeEach
    void setup(RootProject rootProject) {
        rootProject.buildGradle().append("""
            repositories {
                maven {
                    url 'https://test'
                }
                maven {
                    url 'https://demo/'
                }
                mavenCentral() { metadataSources { mavenPom(); ignoreGradleMetadataRedirection() } }
            }

            apply plugin: 'com.palantir.version-props-idea'
            apply plugin: 'idea'
            """);

        Path ideaDir = rootProject.path().resolve(".idea");
        ideaDir.toFile().mkdirs();
    }

    @Test
    void plugin_creates_gcv_maven_repositories_xml_file_in_idea_folder(GradleInvoker gradle, RootProject rootProject)
            throws IOException {
        gradle.withArgs("-Didea.active=true").buildsSuccessfully();

        Path repoFile = rootProject.path().resolve(".idea/gcv-maven-repositories.xml");
        assertThat(repoFile).exists();

        String content = Files.readString(repoFile);
        assertThat(content).contains("<repository url=\"https://test/\"/>");
        assertThat(content).contains("<repository url=\"https://repo.maven.apache.org/maven2/\"/>");
        assertThat(content).contains("<repository url=\"https://demo/\"/>");

        InvocationResult secondRun = gradle.withArgs("-Didea.active=true").buildsSuccessfully();
        assertThat(secondRun.task(":writeMavenRepositories"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.UP_TO_DATE));
    }
}
