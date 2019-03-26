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

import com.palantir.gradle.versions.repogen.DependencyGraph
import com.palantir.gradle.versions.repogen.GradleDependencyGenerator
import groovy.transform.CompileStatic
import nebula.test.ProjectSpec
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.result.ResolvedComponentResult
import spock.util.environment.RestoreSystemProperties

@RestoreSystemProperties
class GradleUtilsTest extends ProjectSpec {

    void setup() {
        System.setProperty('cleanProjectDir', 'false')
        def mavenRepo = generateMavenRepo(
                '(platform) org:platform:1.0 -> org:a:1.0',
                'org:a:1.0')

        project.with {
            // Necessary to bring in platform derivation rules (JavaEcosystemVariantDerivationStrategy)
            apply plugin: 'java-base'

            repositories {
                maven { url "file:///${mavenRepo.getAbsolutePath()}" }
            }
        }
    }

    def "correctly identifies platform in ResolutionResult"() {
        project.with {
            configurations {
                foo
            }
            dependencies {
                foo platform('org:platform:1.0')
            }
        }

        expect:
        project.configurations.foo.incoming.resolutionResult.allComponents { ResolvedComponentResult componentResult ->
            if (componentResult.moduleVersion.group == 'org' && componentResult.moduleVersion.name == 'platform') {
                assert GradleUtils.isPlatform(componentResult.variant.attributes)
            }
        }
    }

    def "correctly identifies platform in unresolved dependencies"() {
        project.with {
            configurations {
                foo
            }
            dependencies {
                foo platform('org:platform:1.0')
            }
        }

        expect:
        ModuleDependency dep = project.configurations.foo.dependencies[0] as ModuleDependency
        GradleUtils.isPlatform(dep.attributes)
    }

    @CompileStatic
    protected File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph)
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(projectDir, "build/testrepogen").toString())
        return generator.generateTestMavenRepo()
    }
}
