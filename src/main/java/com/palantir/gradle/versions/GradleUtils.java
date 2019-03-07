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

import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.util.GradleVersion;

final class GradleUtils {
    private GradleUtils() {}

    private static final GradleVersion GRADLE_VERSION_CATEGORY_AVAILABLE = GradleVersion.version("5.3-rc-1");

    /**
     * Copied from {@code org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport#COMPONENT_CATEGORY} since
     * that's internal. This is only meant to be used with gradle < {@link #GRADLE_VERSION_CATEGORY_AVAILABLE}
     */
    private static final Attribute<String> OLD_COMPONENT_CATEGORY =
            Attribute.of("org.gradle.component.category", String.class);

    /**
     * Returns whether a dependency / component is a non-enforced platform, i.e. what you create with
     * {@link DependencyHandler#platform} or {@link DependencyConstraintHandler#platform}.
     */
    public static boolean isPlatform(AttributeContainer attributes) {
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
}
