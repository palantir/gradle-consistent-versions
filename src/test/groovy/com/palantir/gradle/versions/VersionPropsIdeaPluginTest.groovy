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
import nebula.test.IntegrationSpec
import spock.util.environment.RestoreSystemProperties

import java.util.stream.Collectors

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
            }

            apply plugin: 'com.palantir.version-props-idea'
            apply plugin: 'idea'
        """.stripIndent(true)

        def ideaDir = new File(projectDir, '.idea')
        ideaDir.mkdirs()

        System.setProperty('idea.active', 'true')
    }

    @RestoreSystemProperties
    def 'plugin creates mavenRepositories.xml file in .idea folder'() {
        when:
        runTasksSuccessfully('idea')

        then:
        def repoFile = new File(projectDir, '.idea/mavenRepositories.xml')
        repoFile.exists()


        def mapper = new XmlMapper();
        mapper.registerModule(new GuavaModule());
        def repositories = mapper.readValue(repoFile, MavenRepositories.class);
        def expectedRepositories = List.of("https://test/", "https://demo/").stream()
                .map(ImmutableMavenRepository::of)
                .collect(Collectors.collectingAndThen(Collectors.toList(), ImmutableMavenRepositories::of));

        repositories == expectedRepositories
    }
}
