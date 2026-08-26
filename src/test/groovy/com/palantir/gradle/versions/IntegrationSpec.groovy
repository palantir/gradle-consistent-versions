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

package com.palantir.gradle.versions

import groovy.transform.CompileStatic
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.DependencyGraphNode
import nebula.test.dependencies.GradleDependencyGenerator

class IntegrationSpec extends IntegrationTestKitSpec {
    void setup() {
        keepFiles = true
        debug = true
        settingsFile.createNewFile()
    }

    @CompileStatic
    protected File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph)
        dependencyGraph.nodes = dependencyGraph.nodes.collect { DependencyGraphNode node ->
            new DependencyGraphNode(node.coordinate, node.dependencies, node.status, 17)
        }
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(projectDir, "build/testrepogen").toString())
        new File(generator.gradleRoot, "build.gradle") << '''
            subprojects {
                java.targetCompatibility = JavaVersion.VERSION_1_8
            }
        '''.stripIndent()
        return generator.generateTestMavenRepo()
    }


    /**
     * Runs the specified tasks twice with configuration cache and verifies cache behavior.
     * Returns true if the configuration cache was properly used on the second run.
     */
    boolean runTasksWithConfigurationCache(String... tasks) {
        def firstRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        assert firstRun.output.contains('Configuration cache entry stored.'),
                "Expected first run to store configuration cache, but output was: ${firstRun.output}"

        def secondRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        assert secondRun.output.contains('Configuration cache entry reused.'),
                "Expected second run to reuse configuration cache, but output was: ${secondRun.output}"

        File configCacheDir = new File(projectDir, ".gradle/configuration-cache")
        if (configCacheDir.exists()) {
            configCacheDir.deleteDir()
        }
        assert !configCacheDir.exists(), "Configuration cache directory was not deleted"

        return true
    }
}
