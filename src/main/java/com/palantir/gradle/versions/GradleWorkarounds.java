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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.inject.Inject;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.GradleException;
import org.gradle.api.ProjectState;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.util.GradleVersion;

@SuppressWarnings("UnstableApiUsage")
final class GradleWorkarounds {
    private static final Logger log = Logging.getLogger(GradleWorkarounds.class);

    private static final GradleVersion GRADLE_VERSION_CATEGORY_AVAILABLE = GradleVersion.version("5.3-rc-1");

    /**
     * Copied from {@code org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport#COMPONENT_CATEGORY} since
     * that's internal. This is only meant to be used with gradle < {@link #GRADLE_VERSION_CATEGORY_AVAILABLE}
     */
    private static final Attribute<String> OLD_COMPONENT_CATEGORY =
            Attribute.of("org.gradle.component.category", String.class);

    /** Check if the project is still in the "configuring" stage, i.e. before or including afterEvaluate. */
    static boolean isConfiguring(ProjectState state) {
        try {
            Class<?> stateInternal = Class.forName("org.gradle.api.internal.project.ProjectStateInternal");
            Object internal = stateInternal.cast(state);
            return (boolean) stateInternal.getDeclaredMethod("isConfiguring").invoke(internal);
        } catch (ClassNotFoundException
                | ClassCastException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException e) {
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
                new Class<?>[] {
                        org.gradle.api.internal.provider.CollectionProviderInternal.class,
                        ListProperty.class},
                (proxy, method, args) -> {
                    // Find matching method on CollectionPropertyInternal
                    //org.gradle.api.internal.provider.CollectionProviderInternal
                    if (method
                            .getDeclaringClass() == org.gradle.api.internal.provider.CollectionProviderInternal.class) {
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
     * Work around the following issues with {@link ModuleDependency} attributes.
     * <ul>
     *     <li>Gradle not adding an AttributeFactory to {@link ProjectDependency} with configuration, fixed in
     *     5.3-rc-1</li>
     *     <li>{@link ExternalDependency#copy()} not configuring an AttributeFactory and ALSO not immutably copying the
     *      {@link AttributeContainer}, fixed in 5.6-rc-1.
     *      <p>
     *      See https://github.com/gradle/gradle/pull/9653</li>
     * </ul>
     */
    static <T extends ModuleDependency> T fixAttributesOfModuleDependency(
            ObjectFactory objectFactory,
            T dependency) {
        if (GradleVersion.current().compareTo(GradleVersion.version("5.6")) >= 0
                // Merged on 2019-06-12 so next nightly should be good
                || GradleVersion.current().compareTo(GradleVersion.version("5.6-20190613000000+0000")) >= 0) {
            return dependency;
        }
        log.debug("Fixing attributes of module dependency to work around gradle#9653: {}", dependency.toString());
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

    /**
     * Returns whether a dependency / component is a non-enforced platform, i.e. what you create with
     * {@link DependencyHandler#platform} or {@link DependencyConstraintHandler#platform}.
     */
    static boolean isPlatform(AttributeContainer attributes) {
        if (GradleVersion.current().compareTo(GRADLE_VERSION_CATEGORY_AVAILABLE) < 0) {
            return isPlatformPre53(attributes);
        }
        return isPlatformPost53(attributes);
    }

    private static boolean isPlatformPost53(AttributeContainer attributes) {
        Category category = attributes.getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && Category.REGULAR_PLATFORM.equals(category.getName());
    }

    private static boolean isPlatformPre53(AttributeContainer attributes) {
        String category = attributes.getAttribute(OLD_COMPONENT_CATEGORY);
        return category != null && category.equals("platform");
    }

    static boolean isFailOnVersionConflict(Configuration conf) {
        org.gradle.api.internal.artifacts.configurations.ConflictResolution conflictResolution =
                ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal) conf
                        .getResolutionStrategy()).getConflictResolution();
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
