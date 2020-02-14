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

import com.fasterxml.jackson.databind.ObjectMapper
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class VersionsLockPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.versions-lock"

    @BeforeEach
    void before() {
        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.2.3 -> org.slf4j:slf4j-api:1.7.25",
                "org.slf4j:slf4j-api:1.7.11",
                "org.slf4j:slf4j-api:1.7.20",
                "org.slf4j:slf4j-api:1.7.24",
                "org.slf4j:slf4j-api:1.7.25",
                "org:platform:1.0",
                "junit:junit:4.10",
                "org:test-dep-that-logs:1.0 -> org.slf4j:slf4j-api:1.7.11"
        )
        buildFile << """
            buildscript {
                repositories {
                    maven { url 'https://dl.bintray.com/palantir/releases' }
                }
                dependencies {
                    classpath 'com.palantir.configurationresolver:gradle-configuration-resolver-plugin:0.3.0'
                }
            }
            plugins {
                id '${PLUGIN_NAME}'
            }
            allprojects {
                apply plugin: 'com.palantir.configuration-resolver'
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
            }
        """
    }

    @TestTemplate
    void 'can write locks'() {

        expect:
        runTasks('--write-locks')
        new File(projectDir, "versions.lock").exists()}

    private def standardSetup() {
        buildFile << """
            allprojects {
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

    @TestTemplate
    void 'cannot resolve without a root lock file'() {
        standardSetup()

        expect:
        def result = runTasksAndFail('resolveConfigurations')
        result.output.readLines().any {
            it.matches ".*Root lock file '([^']+)' doesn't exist, please run.*"
        }}

    @TestTemplate
    void 'can resolve without a root lock file if lock file is ignored'() {
        standardSetup()

        expect:
        runTasks('resolveConfigurations', '-PignoreLockFile')}

    @TestTemplate
    void 'global nebula recommendations are superseded by transitive'() {
        buildFile << """
            allprojects {
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
        runTasks('resolveConfigurations')}

    @TestTemplate
    void 'consolidates subproject dependencies'() {
        def expectedError = "Locked by versions.lock"
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
        error.output.contains(expectedError)}

    @TestTemplate
    void 'works on just root project'() {
        buildFile << '''
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent()

        expect:
        runTasks('--write-locks')
        def lines = file('versions.lock').readLines()
        lines.contains('ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)')
        lines.contains('org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)')}

    @TestTemplate
    void 'get a conflict even if no lock files applied'() {
        def expectedError = "Locked by versions.lock"
        standardSetup()

        when: "I write locks"
        runTasks('--write-locks')

        then: "Root lock file has expected resolution result"
        file("versions.lock").text.readLines().any { it.contains('org.slf4j:slf4j-api:1.7.24') }

        then: "I can resolve configurations"
        runTasks('resolveConfigurations')

        when: "I make bar's version constraint incompatible with the force"
        def incompatible = runTasksAndFail("-Pbar_version=1.7.25", 'resolveConfigurations')

        then: "Resolution fails"
        incompatible.output.contains(expectedError)}

    @TestTemplate
    void 'fails fast when subproject that is depended on has same name as root project'() {
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
        // Otherwise the lack of a lock file will throw first
        file('versions.lock') << ""

        expect:
        def error = runTasksAndFail()
        error.output.contains(expectedError)}

    @TestTemplate
    void 'fails fast when multiple subprojects share the same coordinate'() {
        def expectedError = "All subprojects must have unique \$group:\$name"
        buildFile << """
            allprojects {
                group 'same'
            }
        """.stripIndent()
        // both projects will have name = 'a'
        addSubproject("foo:a")
        addSubproject("bar:a")
        // Otherwise the lack of a lock file will throw first
        file('versions.lock') << ""

        expect:
        def error = runTasksAndFail()
        error.output.contains(expectedError)}

    @TestTemplate
    void "detects failOnVersionConflict on locked configuration"() {
        buildFile << """
            apply plugin: 'java'    
            configurations.compileClasspath.resolutionStrategy.failOnVersionConflict()
        """.stripIndent()
        file('versions.lock').text = ''

        expect:
        def failure = runTasksAndFail()
        failure.output.contains('Must not use failOnVersionConflict')}

    @TestTemplate
    void "ignores failOnVersionConflict on non-locked configuration"() {
        buildFile << """
            apply plugin: 'java'    
            configurations {
                foo {
                    resolutionStrategy.failOnVersionConflict()
                }
            }
        """.stripIndent()
        file('versions.lock').text = ''

        expect:
        runTasks()}

    @TestTemplate
    void 'fails if new dependency added that was not in the lock file'() {
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

        runTasks('--write-locks')

        when:
        file('foo/build.gradle') << """
            dependencies {
                compile 'org:b:1.0'
            }
        """.stripIndent()

        then: 'Check fails because locks are not up to date'
        def failure = runTasksAndFail(':check')
        failure.task(':verifyLocks').outcome == TaskOutcome.FAILED
        failure.output.contains(expectedError)

        and: 'Can finally write locks once again'
        runTasks('--write-locks')
        runTasks('verifyLocks')}

    @TestTemplate
    void 'does not fail if unifiedClasspath is unresolvable'() {
        file('versions.lock') << """\
            org.slf4j:slf4j-api:1.7.11 (0 constraints: 0000000)
        """.stripIndent()

        addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api:1.7.20'
            }
        '''.stripIndent())

        expect:
        runTasks('dependencies', '--configuration', 'unifiedClasspath')
        runTasks()}

    @TestTemplate
    void 'fails if dependency was removed but still in the lock file'() {
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

        runTasks('--write-locks')

        when:
        file('foo/build.gradle').text = """
            dependencies {
                compile 'org:a:1.0'
            }
        """.stripIndent()

        then: 'Check fails because locks are not up to date'
        def failure = runTasksAndFail(':check')
        failure.task(':verifyLocks').outcome == TaskOutcome.FAILED
        failure.output.contains(expectedError)

        and: 'Can finally write locks once again'
        runTasks('--write-locks')
        runTasks('verifyLocks')}

    @TestTemplate
    void "why works"() {
        buildFile << '''
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
        '''.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        def result = runTasks('why', '--hash', '400d4d2a') // slf4j-api
        result.output.contains('org.slf4j:slf4j-api:1.7.25')
        result.output.contains('ch.qos.logback:logback-classic -> 1.7.25')}

    @TestTemplate
    void 'does not fail if subproject evaluated later applies base plugin in own build file'() {
        addSubproject('foo', """
            apply plugin: 'java-library'
            dependencies {
                implementation project(':foo:bar')
            }
        """.stripIndent())

        // Need to make sure bar is evaluated after foo, so we're nesting it!
        def subdir = new File(projectDir, 'foo/bar')
        subdir.mkdirs()
        settingsFile << "include ':foo:bar'"
        new File(subdir, 'build.gradle') << """
            apply plugin: 'java-library'
        """.stripIndent()

        expect:
        runTasks('--write-locks')}

    @TestTemplate
    void "locks platform"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                compile platform('org:platform:1.0')
            }
        """.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        file('versions.lock').readLines() == [
                '# Run ./gradlew --write-locks to regenerate this file',
                'org:platform:1.0 (1 constraints: a5041a2c)',
        ]}

    @TestTemplate
    void "verifyLocks is cacheable"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                compile "org.slf4j:slf4j-api:\$depVersion"
            }
        """

        file('gradle.properties') << 'depVersion = 1.7.20'

        when:
        runTasks('--write-locks')

        then: 'verifyLocks is up to date the second time'
        runTasks('verifyLocks').task(':verifyLocks').outcome == TaskOutcome.SUCCESS
        runTasks('verifyLocks').task(':verifyLocks').outcome == TaskOutcome.UP_TO_DATE}


    @TestTemplate
    void "verifyLocks current lock state does not get poisoned by existing lock file"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                compile "org.slf4j:slf4j-api:\$depVersion"
            }
        """

        file('gradle.properties') << 'depVersion = 1.7.20'

        when:
        runTasks('--write-locks')

        then: 'verifyLocks fails if we lower the dep version'
        def fail = runTasksAndFail('verifyLocks', '-PdepVersion=1.7.11')

        and: 'it expects the correct version to be 1.7.11'
        fail.output.contains """\
               > Found dependencies whose dependents changed:
                 -org.slf4j:slf4j-api:1.7.20 (1 constraints: 3c05433b)
                 +org.slf4j:slf4j-api:1.7.11 (1 constraints: 3c05423b)
            """.stripIndent()}

    @TestTemplate
    void "excludes from compileOnly do not obscure real dependency"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3'
            }
            configurations.compileOnly {
                // convoluted, but the idea is to exclude a transitive
                exclude group: 'org.slf4j', module: 'slf4j-api'
            }
        """.stripIndent()

        when:
        runTasks('--write-locks')

        then: 'slf4j-api still appears in the lock file'
        file('versions.lock').readLines() == [
                '# Run ./gradlew --write-locks to regenerate this file',
                'ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)',
                'org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)',
        ]}

    @TestTemplate
    void "can resolve configuration dependency"() {
        addSubproject("foo", """
            apply plugin: 'java'
            dependencies {
                compile project(path: ":bar", configuration: "fun") 
            }
        """.stripIndent())

        addSubproject("bar", """
            configurations {
                fun
            }
            
            dependencies {
                fun 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent())

        // Make sure that we can still add dependencies to the original 'fun' configuration after resolving lock state.
        //
        // Adding a constraint to 'fun' calls Configuration.preventIllegalMutation() which fails if observedState is
        // GRAPH_RESOLVED or ARTIFACTS_RESOLVED. That would happen if a configuration that extends from it has been
        // resolved.
        buildFile << """
            configurations.unifiedClasspath.incoming.afterResolve {
                project(':bar').dependencies.constraints {
                    fun 'some:other-dep'
                }
            }
        """.stripIndent()

        expect:
        runTasks('--write-locks', 'classes')}

    @TestTemplate
    void "inter-project normal dependency works"() {
        addSubproject("foo", """
            apply plugin: 'java'
            dependencies {
                compile project(":bar") 
            }
        """.stripIndent())

        addSubproject("bar", """
            apply plugin: 'java'
        """.stripIndent())

        expect:
        runTasks('--write-locks', 'classes')}

    @TestTemplate
    void "test dependencies appear in a separate block"() {
        buildFile << """
            apply plugin: 'java'           
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3'
                testCompile 'org:test-dep-that-logs:1.0'
            }
        """.stripIndent()

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew --write-locks to regenerate this file
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)
             
            [Test dependencies]
            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
        """.stripIndent()
        file('versions.lock').text == expected}

    @TestTemplate
    void "locks dependencies from extra source sets that end in test"() {
        buildFile << """
            apply plugin: 'java'
            sourceSets {
                eteTest
            }           
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3'
                testCompile 'junit:junit:4.10'
                eteTestCompile 'org:test-dep-that-logs:1.0'
            }
        """.stripIndent()

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew --write-locks to regenerate this file
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)
             
            [Test dependencies]
            junit:junit:4.10 (1 constraints: d904fd30)
            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
        """.stripIndent()
        file('versions.lock').text == expected}

    @TestTemplate
    void "versionsLock.testProject() works"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                compile 'junit:junit:4.10'
            }
            
            versionsLock.testProject()
        """.stripIndent()

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew --write-locks to regenerate this file
             
            [Test dependencies]
            junit:junit:4.10 (1 constraints: d904fd30)
        """.stripIndent()
        file('versions.lock').text == expected}

    @TestTemplate
    void "constraints on production do not affect scope of test only dependencies"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                constraints {
                    compile 'ch.qos.logback:logback-classic:1.2.3'
                }
                dependencies {
                    testCompile 'ch.qos.logback:logback-classic'
                }
            }
        """.stripIndent()

        expect:
        runTasks('--write-locks')
        def expected = """\
            # Run ./gradlew --write-locks to regenerate this file
             
            [Test dependencies]
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
        """.stripIndent()
        file('versions.lock').text == expected}

    @TestTemplate
    void "published constraints are derived from filtered lock file"() {
        buildFile << """
            allprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """.stripIndent()

        addSubproject('foo', """
            dependencies {
                compile 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent())

        addSubproject('bar', """
            dependencies {
                compile 'junit:junit:4.10'
            }
        """.stripIndent())

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent()
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        then: "foo's metadata file has the right dependency constraints"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [logbackDep, slf4jDep]),
        ] as Set

        and: "bar's metadata file has the right dependency constraints"
        def barMetadataFilename = new File(projectDir, "bar/build/publications/maven/module.json")
        def barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile)

        barMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [junitDep]),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [junitDep]),
        ] as Set}

    @TestTemplate
    void "can depend on artifact"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation "junit:junit:4.10@zip"
            }
        """.stripIndent()

        expect:
        runTasks("--write-locks")}

    @TestTemplate
    void "direct test dependency that is also a production transitive ends up in production"() {
        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'org.slf4j:slf4j-api:1.7.25'
            }
        """.stripIndent()

        expect:
        runTasks("--write-locks")
        file('versions.lock').text == """\
            # Run ./gradlew --write-locks to regenerate this file
            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)
            org.slf4j:slf4j-api:1.7.25 (2 constraints: 8012a437)
        """.stripIndent()}
}
