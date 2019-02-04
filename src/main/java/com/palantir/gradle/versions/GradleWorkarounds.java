/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.collect.Maps;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.gradle.api.ProjectState;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class GradleWorkarounds {
    private static final Logger log = Logging.getLogger(GradleWorkarounds.class);

    /** Check if the project is still in the "configuring" stage, i.e. before or including afterEvaluate. */
    static boolean isConfiguring(ProjectState state) {
        try {
            Class<?> stateInternal = Class.forName("org.gradle.api.internal.project.ProjectStateInternal");
            Object internal = stateInternal.cast(state);
            return (boolean) stateInternal.getDeclaredMethod("isConfiguring").invoke(internal);
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException
                | IllegalAccessException | InvocationTargetException e) {
            log.warn("Couldn't use ProjectStateInternal to determine whether project is configuring", e);
            // This is an approximation the public API exposes.
            // It will give us a false negative if we're in 'afterEvaluate'
            return !state.getExecuted();
        }
    }

    static void mergeImportsWithVersions(Element root) {
        Document doc = root.getOwnerDocument();
        Optional<Node> dependenciesNode =
                getSubnode(root, "dependencyManagement").flatMap(n -> getSubnode(n, "dependencies"));
        if (!dependenciesNode.isPresent()) {
            return;
        }
        Stream<Node> dependencies = nodesToStream(dependenciesNode.get().getChildNodes())
                .filter(node -> "dependency".equals(node.getNodeName()));
        Map<String, List<Node>> dependenciesByCoordinate = dependencies
                .flatMap(node -> {
                    Map<String, String> map = parseDependencyNode(node);
                    String groupId = map.get("groupId");
                    String artifactId = map.get("artifactId");
                    if (artifactId == null || groupId == null) {
                        return Stream.of();
                    }
                    String key = groupId + ":" + artifactId;
                    return Stream.of(Maps.immutableEntry(key, node));
                })
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        dependenciesByCoordinate.values().stream().filter(nodes -> nodes.size() > 1).forEach(nodes -> {
            // Is there a node under scope `import` but without a version?
            Optional<Node> badImportingNode =
                    nodes.stream()
                            .filter(n -> {
                                Map<String, String> map = parseDependencyNode(n);
                                return Objects.equals("import", map.get("scope")) && !map.containsKey("version");
                            }).findAny();
            badImportingNode.ifPresent(importingNode -> {
                Node nodeWithVersion = nodes
                        .stream()
                        .filter(n -> {
                            Map<String, String> map = parseDependencyNode(n);
                            return map.containsKey("version");
                        })
                        .findAny()
                        .orElseThrow(() -> new RuntimeException("Couldn't find dependencyManagement node that "
                                + "specifies a version for this import node: " + importingNode));
                Map<String, String> nodeWithVersionMap = parseDependencyNode(nodeWithVersion);
                if (nodes.size() > 2) {
                    throw new RuntimeException("Did not expect more than two conflicting nodes in "
                            + "dependencyManagement: " + nodes);
                }
                nodeWithVersion.getParentNode().removeChild(nodeWithVersion);
                importingNode.appendChild(createProperty(doc, "version", nodeWithVersionMap.get("version")));
            });
        });
    }

    private static Element createProperty(Document doc, String groupId, String text) {
        Element element = doc.createElement(groupId);
        element.setTextContent(text);
        return element;
    }

    private static Stream<Node> nodesToStream(NodeList nodes) {
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item);
    }

    private static Optional<Node> getSubnode(Node node, String subnodeName) {
        NodeList childNodes = node.getChildNodes();
        return nodesToStream(childNodes).filter(n -> subnodeName.equals(n.getNodeName())).findAny();
    }

    private static Map<String, String> parseDependencyNode(Node node) {
        return nodesToStream(node.getChildNodes())
                .flatMap(n -> (n instanceof Element) ? Stream.of(n) : Stream.of())
                .collect(Collectors.toMap(Node::getNodeName, Node::getTextContent));
    }

    private GradleWorkarounds() {}
}
