/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Links: only absolute https URLs with a host and without credentials, and recognition of a link's
 * service by its host.
 *
 * @author Anders Jacobsson
 */
class LinksTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://drive.google.com/drive/folders/abc?usp=sharing",
            "HTTPS://Docs.Google.com/document/d/x",
            "https://youtu.be/abc",
            "https://example.org:8443/a#b",
            "  https://1drv.ms/w/s!abc  "
    })
    void httpsLinksAreAccepted(final String url) {
        assertThat(Links.requireHttps(url, 2000)).isEqualTo(url.strip());
    }

    @ParameterizedTest(name = "{0} is refused with {1}")
    @CsvSource(delimiter = '|', value = {
            "http://drive.google.com/x          | error.link.https",
            "javascript:alert(1)                | error.link.https",
            "ftp://example.org/file             | error.link.https",
            "data:text/html,hej                 | error.link.https",
            "https://kim:hemligt@example.org/x  | error.link.credentials",
            "https://kim@example.org/x          | error.link.credentials",
            "https://exa mple.org/x             | error.link.invalid",
            "https://-bad-.org/x                | error.link.invalid",
            "https://localhost/x                | error.link.invalid",
            "https:///x                         | error.link.invalid",
            "https:example.org                  | error.link.invalid",
            "drive.google.com/x                 | error.link.https",
            "'   '                              | error.link.invalid"
    })
    void otherLinksAreRefused(final String url, final String code) {
        assertThatThrownBy(() -> Links.requireHttps(url, 2000))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("detailMessageCode", code);
    }

    @Test
    void aLinkHasAMaximumLength() {
        final String url = "https://example.org/" + "a".repeat(100);

        assertThatThrownBy(() -> Links.requireHttps(url, 100)).hasFieldOrPropertyWithValue("detailMessageCode", "error.link.tooLong");
        assertThatThrownBy(() -> Links.requireHttps(null, 100)).hasFieldOrPropertyWithValue("detailMessageCode", "error.link.invalid");
    }

    @Test
    void theHostIsLowerCased() {
        assertThat(Links.host("https://Drive.Google.COM/x")).isEqualTo("drive.google.com");
    }

    @Test
    void serviceHostsAreLowerCaseHostNames() {
        assertThat(Links.validHost(" Drive.Google.com ")).isEqualTo("drive.google.com");
        assertThat(Links.validHost("1drv.ms")).isEqualTo("1drv.ms");
        for (final String bad : new String[]{"https://drive.google.com", "drive.google.com/x", "localhost", "under_score.org", "-a.org", "", "a..b"}) {
            assertThatThrownBy(() -> Links.validHost(bad)).as(bad).hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.host.invalid");
        }
    }

    @ParameterizedTest(name = "{0} belongs to {1}")
    @CsvSource({
            "drive.google.com,       GOOGLE_DRIVE",
            "docs.google.com,        GOOGLE_DRIVE",
            "www.youtube.com,        YOUTUBE",
            "m.youtube.com,          YOUTUBE",
            "youtu.be,               YOUTUBE",
            "fgv.sharepoint.com,     ONEDRIVE",
            "notyoutube.com,         ",
            "google.com,             ",
            "sharepoint.com.evil.org, "
    })
    void aLinkIsRecognisedByItsHostOrAParentDomain(final String host, final String expected) {
        assertThat(Links.recognise(host, TestLinkServices.seeded()).map(LinkService::getId).orElse(null)).isEqualTo(expected);
    }

    @Test
    void theFirstActiveServiceInListOrderWins() {
        final LinkService spexflix = TestLinkServices.service("SPEXFLIX", "Spexflix", 0, "video.fgv.nu");
        final LinkService archive = TestLinkServices.service("ARCHIVE", "Arkiv", 1, "fgv.nu");

        assertThat(Links.recognise("video.fgv.nu", List.of(spexflix, archive))).contains(spexflix);
        assertThat(Links.recognise("www.fgv.nu", List.of(spexflix, archive))).contains(archive);
        spexflix.setRetiredAt(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(Links.recognise("video.fgv.nu", List.of(spexflix, archive))).contains(archive);
    }
}
