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
import org.gradle.api.GradleException
import org.gradle.api.plugins.JavaPlugin

class GetVersionPluginSpec extends ProjectSpec {

    def 'apply does not throw exceptions'() {
        when:
        project.apply plugin: GetVersionPlugin

        then:
        noExceptionThrown()
    }

    def 'apply is idempotent'() {
        when:
        project.apply plugin: GetVersionPlugin
        project.apply plugin: GetVersionPlugin

        then:
        noExceptionThrown()
    }

    def 'function is callable from groovy with string & configuration args'() {
        when:
        project.apply plugin: GetVersionPlugin
        project.apply plugin: JavaPlugin
        project.ext.getVersion('com.google.guava:guava', project.configurations.compile)

        then:
        def ex = thrown(GradleException)
        ex.message.contains "Unable to find 'com.google.guava:guava' in configuration ':compile'"
    }

    def 'function is callable from groovy with two string args & configuration arg'() {
        when:
        project.apply plugin: GetVersionPlugin
        project.apply plugin: JavaPlugin
        project.ext.getVersion('com.google.guava', 'guava', project.configurations.compile)

        then:
        def ex = thrown(GradleException)
        ex.message.contains "Unable to find 'com.google.guava:guava' in configuration ':compile'"
    }


    def 'function is callable from groovy with two string args'() {
        when:
        project.apply plugin: GetVersionPlugin
        project.apply plugin: JavaPlugin
        project.getConfigurations().create(VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME)
        project.ext.getVersion('com.google.guava', 'guava')

        then:
        def ex = thrown(GradleException)
        ex.message.contains "Unable to find 'com.google.guava:guava' in configuration ':unifiedClasspath'"
    }
}
