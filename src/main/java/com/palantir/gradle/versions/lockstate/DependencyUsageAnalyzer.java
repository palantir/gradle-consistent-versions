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

package com.palantir.gradle.versions.lockstate;

import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;

public final class DependencyUsageAnalyzer {
    // TODO: cache queries
    private final FullLockState fullLockState;

    public DependencyUsageAnalyzer(FullLockState fullLockState) {
        this.fullLockState = fullLockState;
    }

    public boolean isUsedByProject(ModuleVersionIdentifier moduleVersion, String projectPath) {
        return isUsedByProject(moduleVersion, projectPath, UsageType.DIRECT_OR_TRANSITIVE);
    }

    public boolean isUsedByProject(ModuleVersionIdentifier moduleVersion, String projectPath, UsageType usageType) {
        MyModuleVersionIdentifier key = MyModuleVersionIdentifier.copyOf(moduleVersion);

        Dependents productionDependents = fullLockState.productionDeps().get(key);
        Dependents testDependents = fullLockState.testDeps().get(key);

        if (productionDependents == null && testDependents == null) {
            return false;
        }

        return switch (usageType) {
            case DIRECT_ONLY -> isDirectlyUsedByProject(productionDependents, testDependents, projectPath);
            case TRANSITIVE_ONLY -> isTransitivelyUsedByProject(productionDependents, testDependents, projectPath);
            case DIRECT_OR_TRANSITIVE ->
                isDirectlyUsedByProject(productionDependents, testDependents, projectPath)
                        || isTransitivelyUsedByProject(productionDependents, testDependents, projectPath);
        };
    }

    private boolean isDirectlyUsedByProject(
            Dependents productionDependents, Dependents testDependents, String projectPath) {
        return checkDirectDependency(productionDependents, projectPath)
                || checkDirectDependency(testDependents, projectPath);
    }

    private boolean checkDirectDependency(Dependents dependents, String projectPath) {
        if (dependents == null) {
            return false;
        }

        return dependents.get().keySet().stream()
                .filter(componentId -> componentId instanceof ProjectComponentIdentifier)
                .map(componentId -> (ProjectComponentIdentifier) componentId)
                .anyMatch(projectId -> projectId.getProjectPath().equals(projectPath));
    }

    private boolean isTransitivelyUsedByProject(
            Dependents productionDependents, Dependents testDependents, String projectPath) {
        Set<MyModuleVersionIdentifier> visited = new HashSet<>();
        Queue<MyModuleVersionIdentifier> toVisit = new ArrayDeque<>();

        Set<ComponentIdentifier> initialDependents = new HashSet<>();
        if (productionDependents != null) {
            initialDependents.addAll(
                    productionDependents.nonProjectConstraints().keySet());
        }
        if (testDependents != null) {
            initialDependents.addAll(testDependents.nonProjectConstraints().keySet());
        }

        for (ComponentIdentifier componentId : initialDependents) {
            MyModuleVersionIdentifier moduleId = extractModuleVersionFromComponent(componentId);
            if (moduleId != null) {
                toVisit.offer(moduleId);
            }
        }

        while (!toVisit.isEmpty()) {
            MyModuleVersionIdentifier current = toVisit.poll();

            if (visited.contains(current)) {
                continue;
            }
            visited.add(current);

            Dependents currentProductionDeps = fullLockState.productionDeps().get(current);
            Dependents currentTestDeps = fullLockState.testDeps().get(current);

            if (isDirectlyUsedByProject(currentProductionDeps, currentTestDeps, projectPath)) {
                return true;
            }

            addNonProjectDependentsToQueue(currentProductionDeps, toVisit, visited);
            addNonProjectDependentsToQueue(currentTestDeps, toVisit, visited);
        }

        return false;
    }

    private void addNonProjectDependentsToQueue(
            Dependents dependents, Queue<MyModuleVersionIdentifier> toVisit, Set<MyModuleVersionIdentifier> visited) {
        if (dependents == null) {
            return;
        }

        for (ComponentIdentifier componentId :
                dependents.nonProjectConstraints().keySet()) {
            MyModuleVersionIdentifier moduleId = extractModuleVersionFromComponent(componentId);
            if (moduleId != null && !visited.contains(moduleId)) {
                toVisit.offer(moduleId);
            }
        }
    }

    private MyModuleVersionIdentifier extractModuleVersionFromComponent(ComponentIdentifier componentId) {
        if (componentId instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier moduleComponentId) {
            return MyModuleVersionIdentifier.of(
                    moduleComponentId.getGroup(), moduleComponentId.getModule(), moduleComponentId.getVersion());
        }
        return null;
    }

    public enum UsageType {
        DIRECT_ONLY,
        TRANSITIVE_ONLY,
        DIRECT_OR_TRANSITIVE
    }
}
