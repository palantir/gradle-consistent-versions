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
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.palantir.gradle.versions.ideapluginsettings.Component;
import com.palantir.gradle.versions.ideapluginsettings.ImmutableComponent;
import com.palantir.gradle.versions.ideapluginsettings.ImmutableListOption;
import com.palantir.gradle.versions.ideapluginsettings.ImmutableOption;
import com.palantir.gradle.versions.ideapluginsettings.ImmutableProjectSettings;
import com.palantir.gradle.versions.ideapluginsettings.ListOption;
import com.palantir.gradle.versions.ideapluginsettings.Option;
import com.palantir.gradle.versions.ideapluginsettings.ProjectSettings;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VersionsPropsIdeaPlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(VersionsPropsIdeaPlugin.class);

    @Override
    public void apply(Project target) {
        if (!Boolean.getBoolean("idea.active")) {
            return;
        }
        configureIntellij(target);
    }

    private static void configureIntellij(Project target) {

        Set<String> repositoryUrls = target.getRootProject().getAllprojects().stream()
                .flatMap(project -> project.getRepositories().withType(MavenArtifactRepository.class).stream())
                .map(MavenArtifactRepository::getUrl)
                .map(Object::toString)
                .map(url -> url.endsWith("/") ? url : url + "/")
                .collect(Collectors.toSet());
        File file = target.file(".idea/gradle-consistent-versions-plugin-settings.xml");

        List<ListOption> listOfOptions =
                repositoryUrls.stream().map(ImmutableListOption::of).collect(Collectors.toList());

        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(new GuavaModule());

        try {
            if (file.exists()) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(file, updateIdeaXml(file, mapper, listOfOptions));
            } else {
                mapper.writerWithDefaultPrettyPrinter().writeValue(file, createIdeaXml(listOfOptions));
            }
        } catch (IOException e) {
            log.error("Failed to configure Intellij", e);
        }
    }

    private static ProjectSettings updateIdeaXml(File file, XmlMapper mapper, List<ListOption> listOfOptions)
            throws IOException {
        ProjectSettings existingProjectSettings = mapper.readValue(file, ProjectSettings.class);
        List<Option> options = existingProjectSettings.component().options().stream()
                .map(option -> {
                    if (option.name().equals("mavenRepositories")) {
                        return ImmutableOption.of("mavenRepositories", null, listOfOptions);
                    }
                    return option;
                })
                .collect(Collectors.toList());

        Component updatedComponent =
                ImmutableComponent.of(existingProjectSettings.component().name(), options);

        return ImmutableProjectSettings.of(existingProjectSettings.version(), updatedComponent);
    }

    private static ProjectSettings createIdeaXml(List<ListOption> listOfOptions) {
        Option enableOption = ImmutableOption.of("enabled", "true", null);

        Option mavenRepositoriesList = ImmutableOption.of("mavenRepositories", null, listOfOptions);

        Component component = ImmutableComponent.of(
                "GradleConsistentVersionsSettings", Arrays.asList(enableOption, mavenRepositoriesList));

        return ImmutableProjectSettings.of("4", component);
    }
}
