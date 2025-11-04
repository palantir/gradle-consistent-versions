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
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;

@GradlePluginTests
final class VersionPropsIdeaPluginIntegrationSpec {

    @BeforeEach
    void setup(RootProject rootProject) throws IOException {
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

        Files.createDirectories(rootProject.path().resolve(".idea"));
    }

    @Test
    void plugin_creates_gcv_maven_repositories_xml_file_in_idea_folder(GradleInvoker gradle, RootProject project)
            throws Exception {
        // We run the first time
        gradle.withArgs("-Didea.active=true").buildsSuccessfully();

        // We generate the correct config
        Path repoFile = project.path().resolve(".idea/gcv-maven-repositories.xml");
        assertThat(repoFile).exists();

        String expectedXml = """
            <repositories>
              <repository url="https://test/"/>
              <repository url="https://repo.maven.apache.org/maven2/"/>
              <repository url="https://demo/"/>
            </repositories>
            """.trim();

        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(repoFile.toFile());

        assertThat(nodeToXmlString(doc.getDocumentElement())).isEqualTo(expectedXml);

        // We run the second time
        InvocationResult secondRun = gradle.withArgs("-Didea.active=true").buildsSuccessfully();

        // If nothing has changed, the task is then up-to-date
        assertThat(secondRun).task(":writeMavenRepositories").upToDate();
    }

    private static String nodeToXmlString(Node node) throws Exception {
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
        LSSerializer serializer = impl.createLSSerializer();
        serializer.getDomConfig().setParameter("xml-declaration", false);
        LSOutput lsOutput = impl.createLSOutput();
        lsOutput.setEncoding("UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lsOutput.setByteStream(baos);
        serializer.write(node, lsOutput);
        return baos.toString("UTF-8").trim();
    }
}
