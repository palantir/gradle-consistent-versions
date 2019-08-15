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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.GradleException;
import org.gradle.api.ProjectState;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@SuppressWarnings("UnstableApiUsage")
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

    /**
     * Allow a {@link ListProperty} to be used with {@link DomainObjectCollection#addAllLater}.
     * <p>
     * Pending fix: https://github.com/gradle/gradle/pull/10288
     */
    @SuppressWarnings("unchecked")
    static <T> ListProperty<T> fixListProperty(ListProperty<T> property) {
        Class<?> propertyInternalClass = org.gradle.api.internal.provider.CollectionPropertyInternal.class;
        return (ListProperty<T>) Proxy.newProxyInstance(GradleWorkarounds.class.getClassLoader(),
                new Class<?>[]{
                        org.gradle.api.internal.provider.CollectionProviderInternal.class,
                        ListProperty.class},
                (proxy, method, args) -> {
                    // Find matching method on CollectionPropertyInternal
                    //org.gradle.api.internal.provider.CollectionProviderInternal
                    if (method.getDeclaringClass()
                            == org.gradle.api.internal.provider.CollectionProviderInternal.class) {
                        if (method.getName().equals("getElementType")) {
                            // Proxy to `propertyInternalClass` which we know DefaultListProperty implements.
                            return propertyInternalClass.getMethod(method.getName(), method.getParameterTypes())
                                    .invoke(property, args);
                        } else if (method.getName().equals("size")) {
                            return property.get().size();
                        }
                        throw new GradleException(String.format(
                                "Could not proxy method '%s' to object %s",
                                method,
                                property));
                    } else {
                        return method.invoke(property, args);
                    }
                });
    }

    /**
     * Work around gradle < 5.3.rc-1 not adding an AttributeFactory to {@link ProjectDependency} with configuration,
     * and {@link ExternalDependency#copy()} not configuring an AttributeFactory and ALSO not immutably copying the
     * {@link AttributeContainer}.
     */
    static <T extends ModuleDependency> T fixAttributesOfModuleDependency(
            ObjectFactory objectFactory, T dependency) {
        org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency abstractModuleDependency =
                (org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency) dependency;
        org.gradle.api.internal.attributes.ImmutableAttributesFactory factory =
                objectFactory.newInstance(Extractors.class).attributesFactory;
        abstractModuleDependency.setAttributesFactory(factory);
        // We might have a copied AttributeContainer, so get it immutably, then create a new mutable one.
        org.gradle.api.internal.attributes.AttributeContainerInternal currentAttributes =
                (org.gradle.api.internal.attributes.AttributeContainerInternal) dependency.getAttributes();

        try {
            Method method = org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency.class
                    .getDeclaredMethod(
                            "setAttributes",
                            org.gradle.api.internal.attributes.AttributeContainerInternal.class);
            method.setAccessible(true);
            method.invoke(dependency, factory.mutable(currentAttributes));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to get AttributeContainerInternal#setAttributes", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to invoke AttributeContainerInternal#setAttributes", e);
        }
        return dependency;
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

    static boolean isFailOnVersionConflict(Configuration conf) {
        org.gradle.api.internal.artifacts.configurations.ConflictResolution conflictResolution =
                ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal)
                         conf.getResolutionStrategy()).getConflictResolution();
        return conflictResolution == org.gradle.api.internal.artifacts.configurations.ConflictResolution.strict;
    }

    static class Extractors {
        private final org.gradle.api.internal.attributes.ImmutableAttributesFactory attributesFactory;

        @Inject
        @SuppressWarnings("RedundantModifier")
        public Extractors(org.gradle.api.internal.attributes.ImmutableAttributesFactory attributesFactory) {
            this.attributesFactory = attributesFactory;
        }
    }

    private GradleWorkarounds() {}
}
