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

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ConsistentVersionsPlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }
        project.getPluginManager().apply(VersionsLockPlugin.class);
        project.getPluginManager().apply(VersionsPropsPlugin.class);
        project.getPluginManager().apply(GetVersionPlugin.class);

        project.allprojects(proj -> {
            proj.getPluginManager().withPlugin("java", plugin -> {
                proj.getPluginManager().apply(FixLegacyJavaConfigurationsPlugin.class);
            });
        });
    }

}
