package com.palantir.gradle.versions

import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Unroll

@Unroll
class GradleModuleMetadataVersionAlignmentSpec extends IntegrationSpec {

    void setup() {
        def localRepo = new File(projectDir, 'local-repo')

        // Simulate a real scenario: external repo has older versions and a dependency that uses them
        File externalRepo = generateMavenRepo(
                // Old versions of our modules (simulating previously published versions)
                "com.mycompany:module-a:1.0.0",
                "com.mycompany:module-b:1.0.0",
                // External library that depends on just module-a:1.0.0
                // This is the problematic dependency that would cause version skew
                "com.external:some-library:1.0.0 -> com.mycompany:module-a:1.0.0"
        )

        //language=gradle
        buildFile << """
            plugins {
                id 'com.palantir.gradle-module-metadata-constraints-plugin'
            }
            
            allprojects {
                group = 'com.mycompany'
                version = '2.0.0'
                
                repositories {
                    maven { url "file:///${externalRepo.getAbsolutePath()}" }
                }
            }
        """

        //language=gradle
        def producerBuildGradle = """
            apply plugin: 'java'
            apply plugin: 'maven-publish'
                    
            publishing {
                repositories {
                    maven {
                        url "file:///${localRepo.absolutePath}"
                        }
                    }
                publications {
                    maven(MavenPublication) {
                         from components.java
                    }
                }
            }
        """

        addSubproject('module-a', producerBuildGradle)
        addSubproject('module-b', producearBuildGradle)

        // Consumer subproject that will test the published artifacts
        //language=gradle
        addSubproject('consumer-test', """
            apply plugin: 'java'
            
            // Create a configuration that will only use repository artifacts as we are testing within the same  project
            configurations {
                testAlignment {
                    canBeResolved = true
                    canBeConsumed = false
                }
            }
            
            repositories {
                // Repository with our published modules (will be populated after publish)
                maven { 
                    url "file:///${localRepo.absolutePath}"
                    content {
                        includeGroup 'com.mycompany'
                    }
                }
                // Repository with external dependencies
                maven { 
                    url "file:///${externalRepo.getAbsolutePath()}"
                }
            }
            
            dependencies {
                testAlignment 'com.mycompany:module-b:2.0.0'
                testAlignment 'com.external:some-library:1.0.0'
            }
            
            tasks.register('checkVersions'){
                dependsOn ':module-a:publish', ':module-b:publish'
                doLast {
                    def resolved = [:]
                    configurations.testAlignment.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.mycompany') {
                            resolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }
                    
                    println "Resolved versions:"
                    resolved.each { k, v -> println "  \${k}: \${v}" }
                    
                    def versions = resolved.values() as Set
                    assert versions.size() == 1 : "Modules should be aligned! Got: \${versions}"
                    assert versions.first() == '2.0.0' : "Should align to latest version, got: \${versions.first()}"
                    println "SUCCESS: Both modules aligned to version 2.0.0"
                }
            }
        """)

        runTasks('--write-locks')
        runTasks(':module-a:publish', ':module-b:publish')
    }

    def 'publishPlatformConstraints=true prevents version skew between modules from same repository'() {
        when:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        then:
        def result = runTasks(':consumer-test:checkVersions')

        then:
        result.tasks(TaskOutcome.SUCCESS).path.contains(':consumer-test:checkVersions')
        result.output.contains("SUCCESS: Both modules aligned to version 2.0.0")
    }

    def 'publishPlatformConstraints=false have version skew between modules from same repository'() {
        when:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = false'

        then:
        def result = runTasksAndFail(':consumer-test:checkVersions')

        then:
        result.tasks(TaskOutcome.FAILED).path.contains(':consumer-test:checkVersions')
        result.output.contains("Modules should be aligned! Got: [2.0.0, 1.0.0]")
    }
}
