package com.palantir.gradle.versions

import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

@Unroll
class GradleModuleMetadataConstraintsPluginIntegrationSpec extends IntegrationSpec {

    File repo

    void setup() {
        // Setup external repository with older versions
        repo = generateMavenRepo(
                "com.palantir:service-a:1.0.0",
                "com.palantir:service-b:1.0.0",
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0"
        )

        //language=gradle
        buildFile << """
            plugins {
                id 'com.palantir.versions-lock'
                id 'com.palantir.gradle-module-metadata-constraints-plugin'
            }
            
            allprojects {
                group = 'com.palantir'
                version = '2.0.0'
                
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
            }
            
            subprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                
                publishing {
                    repositories {
                        maven {
                            url "file:///${repo.absolutePath}"
                        }
                    }
                    publications {
                        maven(MavenPublication) {
                            from components.java
                        }
                    }
                }
            }
        """.stripIndent(true)

        // Create service subprojects
        addSubproject('service-a', '// Service A')
        addSubproject('service-b', '// Service B')
    }

    def "#gradleVersionNumber: published constraints with platform constraints only"() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def serviceAConstraint = new MetadataFile.Dependency(
                group: 'com.palantir',
                module: 'service-a',
                version: [requires: '2.0.0'])
        def serviceBConstraint = new MetadataFile.Dependency(
                group: 'com.palantir',
                module: 'service-b',
                version: [requires: '2.0.0'])

        then: "service-a's metadata file has platform constraints"
        def serviceAMetadataFilename = new File(projectDir, "service-a/build/publications/maven/module.json")
        def serviceAMetadata = new ObjectMapper().readValue(serviceAMetadataFilename, MetadataFile)

        serviceAMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint] as Set)
        ] as Set

        and: "service-b's metadata file has platform constraints"
        def serviceBMetadataFilename = new File(projectDir, "service-b/build/publications/maven/module.json")
        def serviceBMetadata = new ObjectMapper().readValue(serviceBMetadataFilename, MetadataFile)

        serviceBMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=true prevents version skew between modules'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        // Publish the modules
        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project
        File consumerProject = createConsumerProject()

        when:
        def result = GradleRunner.create()
                .withProjectDir(consumerProject)
                .withArguments('checkVersions')
                .withPluginClasspath()
                .withGradleVersion(gradleVersionNumber)
                .build()

        then:
        result.tasks(TaskOutcome.SUCCESS).path.contains(':checkVersions')
        result.output.contains("SUCCESS: Both modules aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=false allows version skew between modules'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = false'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        // Publish the modules
        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project
        File consumerProject = createConsumerProject()

        when:
        def result = GradleRunner.create()
                .withProjectDir(consumerProject)
                .withArguments('checkVersions')
                .withPluginClasspath()
                .withGradleVersion(gradleVersionNumber)
                .buildAndFail()

        then:
        result.tasks(TaskOutcome.FAILED).path.contains(':checkVersions')
        result.output.contains("Modules should be aligned! Got: [2.0.0, 1.0.0]")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    private File createConsumerProject() {
        File consumerProject = File.createTempDir('consumer-project', '')
        consumerProject.deleteOnExit()
        consumerProject.mkdirs()

        new File(consumerProject, 'settings.gradle') << "rootProject.name = 'consumer'"

        //language=gradle
        new File(consumerProject, 'build.gradle') << """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { 
                    url "file:///${repo.absolutePath}"
                }
            }
            
            dependencies {
                implementation 'com.palantir:service-b:2.0.0'
                implementation 'com.external:some-library:1.0.0'
            }
            
            tasks.register('checkVersions') {
                doLast {
                    def resolved = [:]
                    configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            resolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }
                    
                    def versions = resolved.values() as Set
                    assert versions.size() == 1 : "Modules should be aligned! Got: \${versions}"
                    assert versions.first() == '2.0.0' : "Should align to latest version, got: \${versions.first()}"
                    println "SUCCESS: Both modules aligned to version 2.0.0"
                }
            }
        """.stripIndent(true)

        return consumerProject
    }
}
