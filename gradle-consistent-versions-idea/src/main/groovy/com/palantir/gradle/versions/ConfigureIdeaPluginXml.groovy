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

package com.palantir.gradle.versions

import groovy.xml.XmlNodePrinter
import groovy.xml.XmlParser
import org.gradle.internal.impldep.com.google.common.collect.ImmutableMap
import org.xml.sax.SAXException

import javax.xml.parsers.ParserConfigurationException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ConfigureIdeaPluginXml {
    static void updateIdeaXmlFile(File configurationFile, String minVersion, boolean createIfAbsent) {
        Node rootNode;
        if (configurationFile.isFile()) {
            try {
                rootNode = new XmlParser().parse(configurationFile);
            } catch (IOException | SAXException | ParserConfigurationException e) {
                throw new RuntimeException("Couldn't parse existing configuration file: " + configurationFile, e);
            }
        } else {
            if (!createIfAbsent) {
                return;
            }
            rootNode = new Node(null, "project", ImmutableMap.of("version", "4"));
        }

        configureExternalDependencies(rootNode, minVersion)

        try (BufferedWriter writer = Files.newBufferedWriter(configurationFile.toPath(), StandardCharsets.UTF_8);
             PrintWriter printWriter = new PrintWriter(writer)) {
            XmlNodePrinter nodePrinter = new XmlNodePrinter(printWriter);
            nodePrinter.setPreserveWhitespace(true);
            nodePrinter.print(rootNode);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write back to configuration file: " + configurationFile, e);
        }
    }

    static void configureExternalDependencies(Node rootNode, String minVersion) {
        def externalDependencies = matchOrCreateChild(rootNode, 'component', [name: 'ExternalDependencies'])
        matchOrCreateChild(externalDependencies, 'plugin', [ 'id': 'gradle-consistent-versions' ], ['min-version' : minVersion])
    }

    private static Node matchOrCreateChild(Node base, String name, Map keyAttributes = [:], Map otherAttributes = [:]) {
        Node node = base[name].find { it.attributes().entrySet().containsAll(keyAttributes.entrySet()) } as Node
        if (Optional.ofNullable(node).isEmpty()) {
            return base.appendNode(name, keyAttributes + otherAttributes)
        } else {
            node.attributes().clear()
            node.attributes().putAll(keyAttributes + otherAttributes)
        }
        return node
    }
}