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

import com.google.common.base.Preconditions;
import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public final class VersionsPropsIdeaPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        System.out.println("==");
        Preconditions.checkState(
                target == target.getRootProject(),
                "May only apply com.palantir.version-props-idea to the root project");

        target.getPluginManager().withPlugin("idea", _ideaPlugin -> {
            configureIntellij(target);
        });
    }

    private static void configureIntellij(Project target) {
        if (!Boolean.getBoolean("idea.active")) {
            return;
        }
        List<String> repositoryUrls = target.getRootProject().getAllprojects().stream()
                .flatMap(project -> project.getRepositories().withType(MavenArtifactRepository.class).stream())
                .map(MavenArtifactRepository::getUrl)
                .map(Object::toString)
                .map(url -> url.endsWith("/") ? url : url + "/")
                .distinct()
                .collect(Collectors.toList());

        File ideaDir = new File(target.getRootProject().getProjectDir(), ".idea");

        writeRepositoryUrlsToXml(repositoryUrls, new File(ideaDir, "mavenRepositories.xml"));
    }

    private static void writeRepositoryUrlsToXml(List<String> repositoryUrls, File file) {
        try {
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            // Root element
            Document doc = docBuilder.newDocument();
            Element rootElement = doc.createElement("repositories");
            doc.appendChild(rootElement);

            // Repository elements
            for (String url : repositoryUrls) {
                Element repository = doc.createElement("repository");
                rootElement.appendChild(repository);

                Element urlElement = doc.createElement("url");
                urlElement.setAttribute("value", url);
                repository.appendChild(urlElement);
            }

            // Write the content into XML file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(file);

            transformer.transform(source, result);

        } catch (ParserConfigurationException | TransformerException e) {
            throw new RuntimeException("Failed to write mavenRepositories.xml", e);
        }
    }
}
