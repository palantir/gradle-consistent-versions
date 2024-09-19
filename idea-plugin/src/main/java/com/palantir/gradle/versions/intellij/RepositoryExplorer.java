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

package com.palantir.gradle.versions.intellij;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryExplorer {
    private static final Logger log = LoggerFactory.getLogger(RepositoryExplorer.class);

    private final String baseUrl;

    public RepositoryExplorer(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public final List<Folder> getFolders(DependencyGroup group) {
        String urlString = baseUrl + group.asUrlString();
        Optional<Contents> content = fetchContent(urlString);

        if (content.isEmpty()) {
            log.warn("Page does not exist");
            return new ArrayList<>();
        }

        return fetchFoldersFromUrl(content.get());
    }

    public final List<DependencyVersion> getVersions(DependencyGroup group, DependencyName dependencyPackage) {
        String urlString = baseUrl + group.asUrlString() + dependencyPackage.name() + "/maven-metadata.xml";
        Optional<Contents> content = fetchContent(urlString);

        if (content.isEmpty()) {
            log.warn("Empty metadata content received");
            return new ArrayList<>();
        }

        return parseVersionsFromMetadata(content.get());
    }

    private Optional<Contents> fetchContent(String urlString) {
        try {
            URL url = new URL(urlString);
            return Contents.pageContents(url);
        } catch (MalformedURLException e) {
            log.error("Malformed URL", e);
            return Optional.empty();
        }
    }

    private List<Folder> fetchFoldersFromUrl(Contents pageContents) {
        List<Folder> folders = new ArrayList<>();

        Document doc = Jsoup.parse(pageContents.pageContent());
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            if (href.endsWith("/") && !href.contains(".")) {
                folders.add(Folder.of(href.substring(0, href.length() - 1)));
            }
        }
        return folders;
    }

    private List<DependencyVersion> parseVersionsFromMetadata(Contents metadataContent) {
        List<DependencyVersion> versions = new ArrayList<>();
        try {
            XmlMapper xmlMapper = new XmlMapper();

            Metadata metadata = xmlMapper.readValue(metadataContent.pageContent(), Metadata.class);
            if (metadata.versioning() != null && metadata.versioning().versions() != null) {
                for (String version : metadata.versioning().versions()) {
                    versions.add(DependencyVersion.of(version));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse maven-metadata.xml", e);
        }
        return versions;
    }
}
