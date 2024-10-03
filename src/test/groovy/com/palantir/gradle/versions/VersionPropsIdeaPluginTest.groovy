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

package com.palantir.gradle.versions

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.datatype.guava.GuavaModule
import com.palantir.gradle.versions.ideapluginsettings.Component
import com.palantir.gradle.versions.ideapluginsettings.ImmutableComponent
import com.palantir.gradle.versions.ideapluginsettings.ImmutableListOption
import com.palantir.gradle.versions.ideapluginsettings.ImmutableOption
import com.palantir.gradle.versions.ideapluginsettings.ImmutableProjectSettings
import com.palantir.gradle.versions.ideapluginsettings.ListOption
import com.palantir.gradle.versions.ideapluginsettings.Option
import com.palantir.gradle.versions.ideapluginsettings.ProjectSettings
import nebula.test.IntegrationSpec
import spock.util.environment.RestoreSystemProperties

class VersionPropsIdeaPluginTest extends IntegrationSpec {

    def setup() {
        //language=gradle
        buildFile << """
            repositories {
                maven {
                    url 'https://test'
                }
                maven {
                    url 'https://demo/'
                }
                mavenCentral() { metadataSources { mavenPom(); ignoreGradleMetadataRedirection() } }
            }

            apply plugin: 'com.palantir.version-props-idea'
            apply plugin: 'idea'
        """.stripIndent(true)

        def ideaDir = new File(projectDir, '.idea')
        ideaDir.mkdirs()

        System.setProperty('idea.active', 'true')
    }

    @RestoreSystemProperties

    def "plugin creates gradle-consistent-versions-plugin-settings.xml file in .idea folder"() {
        when:
        runTasksSuccessfully('idea')

        then:
        def repoFile = new File(projectDir, '.idea/gradle-consistent-versions-plugin-settings.xml')
        repoFile.exists()



        def mapper = new XmlMapper()
        mapper.registerModule(new GuavaModule())

        ProjectSettings actualProjectSettings = mapper.readValue(repoFile, ProjectSettings.class)

        // Create the expected ProjectSettings object
        List<ListOption> listOfOptions = [
                ImmutableListOption.of("https://test/"),
                ImmutableListOption.of("https://repo.maven.apache.org/maven2/"),
                ImmutableListOption.of("https://demo/")
        ]

        Option enableOption = ImmutableOption.of("enabled", "true", (List<ListOption>) null);
        Option mavenRepositoriesList = ImmutableOption.of("mavenRepositories", null, listOfOptions)

        Component component = ImmutableComponent.of(
                "GradleConsistentVersionsSettings", [enableOption, mavenRepositoriesList])

        ProjectSettings expectedProjectSettings = ImmutableProjectSettings.of("4", component)

        actualProjectSettings == expectedProjectSettings
    }

    def "plugin only overwrites the repos and not any other settings"() {
        given:
        def initialXml = """
        <project version="4">
          <component name="GradleConsistentVersionsSettings">
            <option name="enabled" value="false"/>
            <option name="mavenRepositories">
              <list>
                <option value="https://oldrepo1/"/>
                <option value="https://oldrepo2/"/>
              </list>
            </option>
          </component>
        </project>
        """.stripIndent(true)
        def repoFile = new File(projectDir, '.idea/gradle-consistent-versions-plugin-settings.xml')
        repoFile.parentFile.mkdirs()
        repoFile.text = initialXml

        when:
        runTasksSuccessfully('idea')

        then:
        repoFile.exists()

        def mapper = new XmlMapper()
        mapper.registerModule(new GuavaModule())

        ProjectSettings actualProjectSettings = mapper.readValue(repoFile, ProjectSettings.class)

        def enabled = actualProjectSettings.component().options().find { it.name() == "enabled" }

        List<ListOption> expectedListOfOptions = [
                ImmutableListOption.of("https://test/"),
                ImmutableListOption.of("https://repo.maven.apache.org/maven2/"),
                ImmutableListOption.of("https://demo/")
        ]
        Option expectedMavenRepositoriesList = ImmutableOption.of("mavenRepositories", null, expectedListOfOptions)

        def actualMavenRepositoriesList = actualProjectSettings.component().options().find { it.name() == "mavenRepositories" }

        assert actualMavenRepositoriesList == expectedMavenRepositoriesList

        assert enabled != null
        assert enabled.value() == "false"
    }

    private static void normalizeOptionLists(ProjectSettings projectSettings) {
        projectSettings.component().options().forEach(option -> {
            if (option.listOptions() == null) {
                option = ImmutableOption.copyOf(option).withListOptions(Collections.emptyList())
            }
        })
    }
}
