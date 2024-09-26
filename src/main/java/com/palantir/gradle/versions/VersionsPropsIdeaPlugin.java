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

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;

public final class VersionsPropsIdeaPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        target.getPluginManager().withPlugin("idea", _ideaPlugin -> {
            try {
                configureIntellij(target);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void configureIntellij(Project target) throws IOException {
        if (!Boolean.getBoolean("idea.active")) {
            return;
        }
        Set<String> repositoryUrls = target.getRootProject().getAllprojects().stream()
                .flatMap(project -> project.getRepositories().withType(MavenArtifactRepository.class).stream())
                .map(MavenArtifactRepository::getUrl)
                .map(Object::toString)
                .map(url -> url.endsWith("/") ? url : url + "/")
                .collect(Collectors.toSet());

        File file = new File(target.getRootProject().getProjectDir(), ".idea/mavenRepositories.xml");

        MavenRepositories mavenRepositories = repositoryUrls.stream()
                .map(ImmutableMavenRepository::of)
                .collect(Collectors.collectingAndThen(Collectors.toList(), ImmutableMavenRepositories::of));

        XmlMapper mapper = new XmlMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, mavenRepositories);
    }
}
