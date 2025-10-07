/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

@Unroll
class VirtualPlatformSettingsPluginIntegrationSpec extends IntegrationSpec {

    File repo

    void setup() {
        repo = generateMavenRepo(
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0",
                "com.external:some-other-library:1.0.0 -> com.palantir:service-c:2.0.0",
                "com.external:some-library:2.0.0 -> com.palantir:service-a:3.0.0",
                "com.external:some-other-library:2.0.0 -> com.palantir:service-c:4.0.0"
        )
        publishVersionToRepo('0.5.0', false)
        publishVersionToRepo('1.0.0', true)
        publishVersionToRepo('2.0.0', true )
        publishVersionToRepo('3.0.0', false)
        publishVersionToRepo('4.0.0', false)

        //language=gradle
        settingsFile.text = """
            plugins { id 'com.palantir.virtual-platform-settings' }
            rootProject.name = 'consumer'
        """.stripIndent(true)
    }

    def '#gradleVersionNumber: buildscript - aligns when external lib pulls in lower version'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.palantir:service-b:2.0.0',
                'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
        ])

        expect:
        assertAlignedTo('buildscript', '2.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: buildscript - aligns when external lib pulls in higher version'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.palantir:service-b:1.0.0',
                'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
        ])

        expect:
        assertAlignedTo('buildscript', '2.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: buildscript - aligns when force lowers version'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.palantir:service-a:2.0.0',
                'com.palantir:service-b:2.0.0'
        ], [forceVersion: 'com.palantir:service-b:1.0.0'])

        expect:
        assertAlignedTo('buildscript', '1.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - aligns when external lib pulls in lower version'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-b:2.0.0',
                'com.external:some-library:1.0.0'
        ])

        expect:
        assertAlignedTo('runtime', '2.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - aligns when external lib pulls in higher version'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-b:1.0.0',
                'com.external:some-other-library:1.0.0'
        ])

        expect:
        assertAlignedTo('runtime', '2.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - aligns when force lowers version'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:2.0.0',
                'com.palantir:service-b:2.0.0'
        ], [forceVersion: 'com.palantir:service-b:1.0.0'])

        expect:
        assertAlignedTo('runtime', '1.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: handles malformed GMM without crashing'() {
        given:
        createMalformedMetadata()
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:1.0.0',
                'com.malformed:bad-metadata:1.0.0'
        ])

        expect:
        runTasks('dependencies').task(':dependencies').outcome == TaskOutcome.SUCCESS

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for buildscript'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.palantir:service-a:3.0.0',
                'com.palantir:service-b:4.0.0'
        ])

        expect:
        assertAlignedTo('buildscript', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for runtime'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:3.0.0',
                'com.palantir:service-b:4.0.0'
        ])

        expect:
        assertAlignedTo('runtime', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for buildscript a'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.external:some-library:2.0.0', // pulls in service-a:3.0.0
                'com.palantir:service-b:4.0.0'
        ])

        expect:
        assertAlignedTo('buildscript', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for runtime a'() {
        given:
        buildFileWithDeps('runtime', [
                'com.external:some-library:2.0.0', // pulls in service-a:3.0.0
                'com.palantir:service-b:4.0.0'
        ])

        expect:
        assertAlignedTo('runtime', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for buildscript b'() {
        given:
        buildFileWithDeps('buildscript', [
                'com.external:some-other-library:2.0.0',  // pulls in service-c:4.0.0
                'com.palantir:service-b:3.0.0'
        ])

        expect:
        assertAlignedTo('buildscript', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns when GMM is missing for runtime b'() {
        given:
        buildFileWithDeps('runtime', [
                'com.external:some-other-library:2.0.0',  // pulls in service-c:4.0.0
                'com.palantir:service-b:4.0.0'
        ])

        expect:
        assertAlignedTo('runtime', '4.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - aligns mixed GMM and POM-only artifacts'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:1.0.0',  // has GMM
                'com.palantir:service-b:3.0.0'   // POM only
        ])

        expect:
        assertAlignedTo('runtime', '3.0.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - works when force lowers to POM-only version'() {
        given:
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:1.0.0',
                'com.palantir:service-b:1.0.0'
        ], [forceVersion: 'com.palantir:service-b:0.5.0'])

        expect:
        assertAlignedTo('runtime', '0.5.0')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: POM fallback - handles malformed POM without crashing'() {
        given:
        createMalformedPomMetadata()
        buildFileWithDeps('runtime', [
                'com.palantir:service-a:1.0.0',
                'com.malformed:bad-pom:1.0.0'
        ])

        expect:
        runTasks('dependencies').task(':dependencies').outcome == TaskOutcome.SUCCESS

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    private void buildFileWithDeps(String scope, List<String> deps, Map options = [:]) {
        def forceVersion = options.forceVersion

        if (scope == 'buildscript') {
            //language=gradle
            buildFile.text = """
                buildscript {
                    repositories { maven { url "file:///${repo.absolutePath}" } }
                    dependencies {
                        ${deps.collect { "classpath '$it'" }.join('\n                        ')}
                    }
                    ${forceVersion ? "configurations.classpath { resolutionStrategy { force '$forceVersion' } }" : ''}
                }
                plugins { id 'java' }
            """.stripIndent(true)
        } else {
            //language=gradle
            buildFile.text = """
                plugins { id 'java' }
                repositories { maven { url "file:///${repo.absolutePath}" } }
                dependencies {
                    ${deps.collect { "implementation '$it'" }.join('\n                    ')}
                }
                ${forceVersion ? "configurations.runtimeClasspath { resolutionStrategy { force '$forceVersion' } }" : ''}
            """.stripIndent(true)
        }
    }

    private void assertAlignedTo(String scope, String expectedVersion) {
        def taskName = "check${scope.capitalize()}Alignment"
        def configName = scope == 'buildscript' ? 'buildscript.configurations.classpath' : 'configurations.runtimeClasspath'

        //language=gradle
        buildFile << """
            tasks.register('$taskName') {
                doLast {
                    def versions = ${configName}.resolvedConfiguration.resolvedArtifacts
                        .findAll { it.moduleVersion.id.group == 'com.palantir' }
                        .collect { it.moduleVersion.id.version }
                        .unique()
                    
                    assert versions.size() == 1 : "Expected alignment, got: \${versions}"
                    assert versions[0] == '$expectedVersion' : "Expected $expectedVersion, got \${versions[0]}"
                    println "SUCCESS: Aligned to $expectedVersion"
                }
            }
        """.stripIndent(true)

        def result = runTasks(taskName)
        assert result.task(":$taskName").outcome == TaskOutcome.SUCCESS
        assert result.output.contains("SUCCESS: Aligned to $expectedVersion")
    }

    private void createMalformedMetadata() {
        def malformedDir = new File(repo, 'com/malformed/bad-metadata/1.0.0')
        malformedDir.mkdirs()
        new File(malformedDir, 'bad-metadata-1.0.0.jar').text = 'fake jar'
        new File(malformedDir, 'bad-metadata-1.0.0.pom').text = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.malformed</groupId>
                <artifactId>bad-metadata</artifactId>
                <version>1.0.0</version>
            </project>
        """.stripIndent()
        new File(malformedDir, 'bad-metadata-1.0.0.module').text = "{ invalid json"
    }

    private void createMalformedPomMetadata() {
        def malformedDir = new File(repo, 'com/malformed/bad-pom/1.0.0')
        malformedDir.mkdirs()
        new File(malformedDir, 'bad-pom-1.0.0.jar').text = 'fake jar'
        new File(malformedDir, 'bad-pom-1.0.0.pom').text = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.malformed</groupId>
                <artifactId>bad-pom</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <!-- Malformed dependency entry -->
                        <dependency>
                            <groupId>com.palantir</groupId>
                            <!-- Missing artifactId -->
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.stripIndent()
    }

    private void publishVersionToRepo(String version, Boolean publishGMM) {
        ['service-a', 'service-b', 'service-c'].each { serviceName ->
            def serviceDir = new File(repo, "com/palantir/${serviceName}/${version}")
            serviceDir.mkdirs()

            new File(serviceDir, "${serviceName}-${version}.jar").text = 'fake jar content'

            new File(serviceDir, "${serviceName}-${version}.pom").text = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.palantir</groupId>
                    <artifactId>${serviceName}</artifactId>
                    <version>${version}</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.palantir</groupId>
                                <artifactId>palantir-virtual-platform</artifactId>
                                <version>${version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.stripIndent(true)

            if (!publishGMM) {
                return;
            }

            new File(serviceDir, "${serviceName}-${version}.module").text = """
                {
                    "formatVersion": "1.1",
                    "component": {
                        "group": "com.palantir",
                        "module": "${serviceName}",
                        "version": "${version}"
                    },
                    "variants": [
                        {
                            "name": "apiElements",
                            "attributes": {
                                "org.gradle.category": "library",
                                "org.gradle.usage": "java-api"
                            },
                            "dependencies": [
                                {
                                    "group": "com.palantir",
                                    "module": "palantir-virtual-platform",
                                    "version": { "requires": "${version}" }
                                }
                            ]
                        },
                        {
                            "name": "runtimeElements",
                            "attributes": {
                                "org.gradle.category": "library",
                                "org.gradle.usage": "java-runtime"
                            },
                            "dependencies": [
                                {
                                    "group": "com.palantir",
                                    "module": "palantir-virtual-platform",
                                    "version": { "requires": "${version}" }
                                }
                            ]
                        }
                    ]
                }
            """.stripIndent(true)
        }
    }
}
