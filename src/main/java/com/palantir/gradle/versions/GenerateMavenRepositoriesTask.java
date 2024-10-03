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

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.immutables.value.Value;

public abstract class GenerateMavenRepositoriesTask extends DefaultTask {

    private static final ObjectMapper XML_MAPPER = new XmlMapper().registerModule(new GuavaModule());

    private static final String MAVEN_REPOSITORIES_FILE_NAME = ".idea/gcv-maven-repositories.xml";

    @Input
    public abstract SetProperty<String> getMavenRepositories();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public GenerateMavenRepositoriesTask() {
        getOutputFile().set(getProject().file(MAVEN_REPOSITORIES_FILE_NAME));
    }

    @TaskAction
    final void action() {
        writeRepositoriesToXml();
    }

    private void writeRepositoriesToXml() {
        File file = getOutputFile().get().getAsFile();
        List<RepositoryConfig> repositories = getMavenRepositories().get().stream()
                .map(ImmutableRepositoryConfig::of)
                .collect(Collectors.toList());
        Repositories wrapped = ImmutableRepositories.of(repositories);

        try {
            XML_MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, wrapped);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableRepositoryConfig.class)
    @JsonSerialize(as = ImmutableRepositoryConfig.class)
    interface RepositoryConfig {

        @Value.Parameter
        @JacksonXmlProperty(isAttribute = true)
        String url();
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableRepositories.class)
    @JsonSerialize(as = ImmutableRepositories.class)
    @JsonRootName("repositories")
    interface Repositories {

        @Value.Parameter
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "repository")
        List<RepositoryConfig> repositories();
    }
}
