/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.versions;

final class PomUtils {
    static void makePlatformPom(File repo, String group, String name, String version) {
        def dir = new File(repo, "${group}/${name}/${version}")
        dir.mkdirs()
        new File(dir, "platform-1.0.pom") << """\
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <packaging>pom</packaging>
              <groupId>${group}</groupId>
              <artifactId>${name}</artifactId>
              <version>${version}</version>
              <dependencyManagement>
                <dependencies>
                </dependencies>
              </dependencyManagement>
            </project>
        """.stripIndent()
    }
}
