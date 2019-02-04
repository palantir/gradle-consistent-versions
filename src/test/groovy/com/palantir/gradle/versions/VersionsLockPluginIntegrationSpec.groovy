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

import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import org.gradle.testkit.runner.BuildResult

class VersionsLockPluginIntegrationSpec extends IntegrationTestKitSpec {

    static def PLUGIN_NAME = "com.palantir.versions-lock"

    void setup() {
        keepFiles = true
        settingsFile.createNewFile()
        buildFile << """
            plugins { id '${PLUGIN_NAME}' }
        """
    }

    def 'can write locks'() {
        expect:
        buildFile << '''
            repositories {
                jcenter()
            }
        '''.stripIndent()
        runTasks('resolveConfigurations', '--write-locks')
        new File(projectDir, "versions.lock").exists()
    }

    private def standardSetup() {
        buildFile << """
            allprojects {
                repositories {
                    jcenter()
                }
                // using nebula in ':baz'
                apply plugin: 'nebula.dependency-recommender'
            }
        """.stripIndent()

        addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api:1.7.24'
            }
        '''.stripIndent())

        addSubproject('bar', '''
            apply plugin: 'java'
            dependencies {
                compile "org.slf4j:slf4j-api:${project.bar_version}"
            }
        '''.stripIndent())
        file("gradle.properties") << "bar_version=1.7.11"

        addSubproject('baz', '''
            apply plugin: 'java'
            dependencies {
                compile "org.slf4j:slf4j-api"
            }
            dependencyRecommendations {
                map recommendations: ['org.slf4j:slf4j-api': '1.7.20']
            }
        '''.stripIndent())

        addSubproject('forced', '''
            apply plugin: 'java'
            dependencies {
                compile "org.slf4j:slf4j-api"
            }
            configurations.all {
                resolutionStrategy {
                    force "org.slf4j:slf4j-api:1.7.20"
                }
            }
        '''.stripIndent())
    }

    def 'can resolve without a root lock file'() {
        setup:
        standardSetup()
        buildFile << '''
            subprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
            }
        '''.stripIndent()

        expect:
        def result = runTasks('resolveConfigurations')
        result.output.readLines().any {
            it.matches ".*Root lock file '([^']+)' doesn't exist, please run.*"
        }
    }

    def 'global nebula recommendations are superseded by transitive'() {
        setup:
        buildFile << """
            allprojects {
                repositories {
                    jcenter()
                }
                apply plugin: 'nebula.dependency-recommender'
                
                dependencyRecommendations {
                    propertiesFile file: rootProject.file('versions.props')
                }
            }
            subprojects {
                apply plugin: 'com.palantir.configuration-resolver'
            }
        """.stripIndent()

        addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
            }
        '''.stripIndent())

        addSubproject('bar', '''
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent())

        buildFile << '''
            subprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
            }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:slf4j-api = 1.7.24'

        expect: "I can write locks"
        runTasks('resolveConfigurations', '--write-locks')

        and: "foo now picks up a higher version than nebula suggested"
        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.25")

        and: "I can resolve"
        runTasks('resolveConfigurations')
    }

    def 'consolidates subproject dependencies'() {
        def expectedError = "Locked by versions.lock"
        setup:
        standardSetup()
        buildFile << '''
            subprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
                apply plugin: 'com.palantir.configuration-resolver'
            }
        '''.stripIndent()

        when: "I write locks"
        runTasks('resolveConfigurations', '--write-locks')

        then: "Lock files are consistent with version resolved at root"
        file("versions.lock").text.readLines().any { it.startsWith('org.slf4j:slf4j-api:1.7.24') }
        [
                "foo/gradle/dependency-locks/runtimeClasspath.lockfile",
                "bar/gradle/dependency-locks/runtimeClasspath.lockfile",
                "baz/gradle/dependency-locks/runtimeClasspath.lockfile",
        ].each {
            assert file(it).text.contains('org.slf4j:slf4j-api:1.7.24')
        }

        then: "Manually forced version overrides unified dependency"
        file("forced/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains('org.slf4j:slf4j-api:1.7.20')

        then: "I can resolve configurations"
        runTasks('resolveConfigurations')

        when: "I make bar's version constraint incompatible with the force"
        BuildResult incompatible = runTasksAndFail("-Pbar_version=1.7.25", 'resolveConfigurations')

        then: "Resolution fails"
        incompatible.output.contains(expectedError)

        when: "I change the version in baz/"
        file("baz/build.gradle") << '''
            dependencyRecommendations {
                map recommendations: ['org.slf4j:slf4j-api': '1.7.25']
            }
        '''.stripIndent()

        then: "Resolution fails"
        def error = runTasksAndFail(':baz:resolveConfigurations')
        error.output.contains(expectedError)
    }

    def 'works on just root project'() {
        buildFile << '''
            apply plugin: 'java'
            repositories {
                jcenter()
            }
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent()

        expect:
        runTasks('--write-locks')
        def lines = file('versions.lock').readLines()
        lines.contains('ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)')
        lines.contains('org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)')
    }

    def 'get a conflict even if no lock files applied'() {
        def expectedError = "Locked by versions.lock"
        setup:
        standardSetup()

        when: "I write locks"
        runTasks('resolveConfigurations', '--write-locks')

        then: "Root lock file has expected resolution result"
        file("versions.lock").text.readLines().any { it.contains('org.slf4j:slf4j-api:1.7.24') }

        then: "I can resolve configurations"
        runTasks('resolveConfigurations')

        when: "I make bar's version constraint incompatible with the force"
        def incompatible = runTasksAndFail("-Pbar_version=1.7.25", 'resolveConfigurations')

        then: "Resolution fails"
        incompatible.output.contains(expectedError)
    }

    def 'fails fast when subproject that is depended on has same name as root project'() {
        def expectedError = "This plugin doesn't work if the root project shares both group and name with a subproject"
        buildFile << """
            allprojects {
                group 'same'
            }
        """.stripIndent()
        settingsFile << "rootProject.name = 'foobar'\n"
        addSubproject("foobar", '''
            apply plugin: 'java-library'
        '''.stripIndent())
        addSubproject("other", '''
            apply plugin: 'java-library'
            dependencies {
                implementation project(':foobar')
            }
        '''.stripIndent())

        expect:
        def error = runTasksAndFail()
        error.output.contains(expectedError)
    }

    def 'fails if new dependency added that was not in the lock file'() {
        def expectedError = "Found dependencies that were not in the lock state"
        DependencyGraph dependencyGraph = new DependencyGraph("org:a:1.0", "org:b:1.0")
        GradleDependencyGenerator generator = new GradleDependencyGenerator(dependencyGraph)
        def mavenRepo = generator.generateTestMavenRepo()

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.absolutePath}" }
            }
            
            subprojects {
                apply plugin: 'java'
            }
        """.stripIndent()

        addSubproject('foo', '''
            dependencies {
                compile 'org:a:1.0'
            }
        '''.stripIndent())

        runTasks(':resolveConfigurations', '--write-locks')

        when:
        file('foo/build.gradle') << """
            dependencies {
                compile 'org:b:1.0'
            }
        """.stripIndent()

        then:
        def failure = runTasksAndFail(':resolveConfigurations')
        failure.output.contains(expectedError)
        runTasks(':resolveConfigurations', '--write-locks')
    }

    def 'fails if dependency was removed but still in the lock file'() {
        def expectedError = "Locked dependencies missing from the resolution result"
        DependencyGraph dependencyGraph = new DependencyGraph("org:a:1.0", "org:b:1.0")
        GradleDependencyGenerator generator = new GradleDependencyGenerator(dependencyGraph)
        def mavenRepo = generator.generateTestMavenRepo()

        buildFile << """
            repositories {
                maven { url "file:///${mavenRepo.absolutePath}" }
            }
            
            subprojects {
                apply plugin: 'java'
            }
        """.stripIndent()

        addSubproject('foo', '''
            dependencies {
                compile 'org:a:1.0'
                compile 'org:b:1.0'
            }
        '''.stripIndent())

        runTasks(':resolveConfigurations', '--write-locks')

        when:
        file('foo/build.gradle').text = """
            dependencies {
                compile 'org:a:1.0'
            }
        """.stripIndent()

        then:
        def failure = runTasksAndFail(':resolveConfigurations')
        failure.output.contains(expectedError)
        runTasks(':resolveConfigurations', '--write-locks')
    }

    def "why works"() {
        buildFile << '''
            apply plugin: 'java'
            repositories { jcenter() } 
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        def result = runTasks('why', '--hash', '400d4d2a') // slf4j-api
        result.output.contains('org.slf4j:slf4j-api:1.7.25')
        result.output.contains('ch.qos.logback:logback-classic -> 1.7.25')
    }
}
