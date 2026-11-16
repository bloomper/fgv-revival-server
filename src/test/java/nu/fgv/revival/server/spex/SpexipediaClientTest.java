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

package nu.fgv.revival.server.spex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Spex, revivals and tasks from Spexipedia, and revivals created there.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexipediaClientTest {

    private static final String URL = "http://spexipedia.test/api/graphql";

    private MockRestServiceServer server;
    private SpexipediaClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer.bindTo(builder).build();
        client = new SpexipediaClient(HttpSyncGraphQlClient.builder(builder.build()).url(URL).build(), RestClient.create());
    }

    @Test
    void originalsAreTheSpexThatAreNotRevivals() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"data": {"spexAll": [{"id": "1", "year": "1956", "title": "Ali Baba", "revival": false},
                                      {"id": "2", "year": "2005", "title": "Ali Baba", "revival": true}]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.originalSpex()).containsExactly(new SpexDto("1", "Ali Baba", 1956, true));
    }

    @Test
    void tasksTellTheEnsembleByTheirCategory() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"data": {"tasks": [
                  {"id": "1", "name": "Roll", "category": {"id": "10", "name": "Ensemble", "actorPresent": true}},
                  {"id": "2", "name": "Trumpet", "category": {"id": "11", "name": "Orkester", "actorPresent": false}}]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.tasks()).extracting(task -> task.category() != null && Boolean.TRUE.equals(task.category().actorPresent()))
                .containsExactly(true, false);
    }

    @Test
    void aSpexHasItsRevivalsAndANewOneIsCreatedThere() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"data": {"spex": {"id": "1", "revivals": [{"id": "400", "year": "2005"}]}}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("spexRevivalCreate")))
                .andExpect(jsonPath("$.variables.parentId").value("1"))
                .andExpect(jsonPath("$.variables.year").value("2028"))
                .andRespond(withSuccess("{\"data\": {\"spexRevivalCreate\": {\"id\": \"500\"}}}", MediaType.APPLICATION_JSON));

        assertThat(client.spexRevivals("1")).containsExactly(new SpexregisterClient.SpexRevival("400", 2005));
        assertThat(client.createSpexRevival("1", 2028)).isEqualTo("500");
        server.verify();
    }

    @Test
    void anUnreachableSpexipediaIsAnUnavailableCallException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.tasks()).isInstanceOfSatisfying(SpexipediaClient.SpexipediaCallException.class,
                e -> assertThat(e.code()).isEqualTo(SpexipediaClient.SpexipediaCallException.SPEXIPEDIA_UNAVAILABLE));
    }
}
