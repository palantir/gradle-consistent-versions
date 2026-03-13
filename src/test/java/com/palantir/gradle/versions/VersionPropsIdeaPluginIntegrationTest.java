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

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.files.arbitrary.ArbitraryFile;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import groovy.util.Node;
import groovy.xml.XmlNodePrinter;
import groovy.xml.XmlParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

@GradlePluginTests
class VersionPropsIdeaPluginIntegrationTest {

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
            """);

        rootProject
                .buildGradle()
                .plugins()
                .add("com.palantir.version-props-idea")
                .add("idea");

        rootProject.directory(".idea").createDirectories();
    }

    @Test
    void plugin_creates_gcv_maven_repositories_xml_file_in_idea_folder(GradleInvoker gradle, RootProject rootProject)
            throws ParserConfigurationException, IOException, SAXException {
        // when: 'we run the first time'
        gradle.withArgs("-Didea.active=true").buildsSuccessfully();

        // then: 'we generate the correct config'
        ArbitraryFile repoFile = rootProject.file(".idea/gcv-maven-repositories.xml");
        repoFile.assertThat().exists();

        // language=xml
        String expectedXml = """
            <repositories>
              <repository url="https://test/"/>
              <repository url="https://repo.maven.apache.org/maven2/"/>
              <repository url="https://demo/"/>
            </repositories>
            """.trim();

        Node projectNode = new XmlParser().parse(repoFile.path().toFile());
        assertThat(nodeToXmlString(projectNode)).isEqualTo(expectedXml);

        // when: 'we run the second time'
        InvocationResult secondRun = gradle.withArgs("-Didea.active=true").buildsSuccessfully();

        // then: "if nothing has changed, the task is then up-to-date"
        assertThat(secondRun).task(":writeMavenRepositories").upToDate();
    }

    private static String nodeToXmlString(Object debugRunConf) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new XmlNodePrinter(new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8)))
                .print((Node) debugRunConf);
        return baos.toString(StandardCharsets.UTF_8).trim();
    }
}
