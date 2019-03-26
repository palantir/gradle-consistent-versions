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

import nebula.test.ProjectSpec
import org.gradle.api.artifacts.ModuleDependency
import spock.util.environment.RestoreSystemProperties

@RestoreSystemProperties
class GradleUtilsTest extends ProjectSpec {

    void setup() {
        System.setProperty('cleanProjectDir', 'false')
        def mavenRepo = new File(project.buildDir, 'mavenrepo')
        def pomFile = new File(mavenRepo, 'org/platform/1.0/platform-1.0.pom')
        pomFile.parentFile.mkdirs()
        pomFile << '''\
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org</groupId>
              <artifactId>platform</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org</groupId>
                    <artifactId>a</artifactId>
                    <version>1.0</version>
                    <scope>compile</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
        '''.stripIndent()

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
        def rcr = project.configurations.foo.incoming.resolutionResult.allComponents.find {
            it.moduleVersion.group == 'org' && it.moduleVersion.name == 'platform'
        }
        GradleUtils.isPlatform(rcr.variant.attributes)
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
}
