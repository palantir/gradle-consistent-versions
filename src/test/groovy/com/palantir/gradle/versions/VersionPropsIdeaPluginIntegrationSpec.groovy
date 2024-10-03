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

class VersionPropsIdeaPluginIntegrationSpec extends IntegrationSpec {

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
    def "plugin creates gcv-maven-repositories.xml file in .idea folder"() {
        when: 'we run the first time'
        runTasksSuccessfully()

        then: 'we generate the correct config'
        def repoFile = new File(projectDir, '.idea/gcv-maven-repositories.xml')
        repoFile.exists()

        // language=xml
        def expectedXml = '''
            <repositories>
              <repository url="https://test/"/>
              <repository url="https://repo.maven.apache.org/maven2/"/>
              <repository url="https://demo/"/>
            </repositories>
        '''.stripIndent(true).trim()

        def projectNode = new XmlParser().parse(repoFile)
        nodeToXmlString(projectNode) == expectedXml

        when: 'we run the second time'
        def secondRun = runTasksSuccessfully()

        then: "if nothing has changed, the task is then up-to-date"
        secondRun.wasUpToDate(":writeMavenRepositories")
    }

    private static String nodeToXmlString(debugRunConf) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        new XmlNodePrinter(new PrintWriter(baos)).print(debugRunConf)
        return baos.toString().trim()
    }
}
