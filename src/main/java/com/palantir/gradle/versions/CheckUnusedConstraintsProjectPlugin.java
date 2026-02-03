/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;

public class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    static final String OUTGOING_USAGE = "check-unused-constraints-module-identifiers";

    @Override
    public final void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, _javaPlugin -> {
            createOutgoingConfiguration(project);
        });
    }

    private static void createOutgoingConfiguration(Project project) {
        TaskProvider<GenerateResolvedModulesTask> generateTask = project.getTasks()
                .register("generateResolvedModules", GenerateResolvedModulesTask.class, task -> {
                    task.getOutputFile()
                            .set(project.getLayout()
                                    .getBuildDirectory()
                                    .file("check-unused-constraints/resolved-modules.txt"));
                    task.getConfigurations()
                            .set(project.provider(() -> GradleConfigurations.getResolvableConfigurations(project)));
                });

        project.getConfigurations().consumable("checkUnusedConstraintsOutgoing", outgoing -> {
            outgoing.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, OUTGOING_USAGE));
            });

            outgoing.getOutgoing().artifact(generateTask.flatMap(GenerateResolvedModulesTask::getOutputFile));
        });
    }
}
