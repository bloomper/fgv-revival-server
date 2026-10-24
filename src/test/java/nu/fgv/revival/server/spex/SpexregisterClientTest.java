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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The M6 spexare calls against a stub of Spexregister's GraphQL API (constitution VII): what is sent, with the acting
 * person, and what is read back.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
class SpexregisterClientTest {

    private static final String URL = "http://spexregister.test/api/graphql";
    private static final String ACTING = "Åsa Öberg (via Nyuppsättning)";

    private MockRestServiceServer server;
    private SpexregisterClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer.bindTo(builder).build();

        final RestClient restClient = builder.build();

        client = new SpexregisterClient(HttpSyncGraphQlClient.builder(restClient).url(URL).build(), restClient);
    }

    @Test
    void theActingPersonIsPercentEncodedAndLimited() {
        assertThat(SpexregisterClient.actingPersonHeader(ACTING)).isEqualTo("%C3%85sa%20%C3%96berg%20%28via%20Nyupps%C3%A4ttning%29");
        assertThat(SpexregisterClient.actingPersonHeader("x".repeat(300))).hasSize(SpexregisterClient.ACTING_PERSON_MAX_LENGTH);
    }

    @Test
    void spexareByIdsReadsAddressesConsentsAndActivities() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Acting-Person", SpexregisterClient.actingPersonHeader(ACTING)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("spexareByIds")))
                .andExpect(jsonPath("$.variables.ids[0]").value("7"))
                .andRespond(withSuccess("""
                        {"data": {"spexareByIds": [{
                          "id": "7", "firstName": "Anna", "lastName": "Andersson", "nickName": "Ankan", "graduation": "E05", "deceased": false,
                          "addresses": [{"id": "70", "type": {"id": "HOME"}, "emailAddress": "anna@example.org", "phone": null, "phoneMobile": "0701234567",
                                         "streetAddress": "Gatan 1", "postalCode": "41101", "city": "Göteborg"}],
                          "consents": [{"id": "71", "value": true, "type": {"id": "REGISTER"}}],
                          "activities": [{"id": "72", "spexActivity": {"id": "73", "spex": {"id": "1", "year": "1956", "title": "Ali Baba", "revival": false}}}]
                        }]}}""", MediaType.APPLICATION_JSON));

        final List<SpexregisterClient.Spexare> found = client.spexareByIds(List.of(7L), ACTING);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().home()).hasValueSatisfying(home -> assertThat(home.emailAddress()).isEqualTo("anna@example.org"));
        assertThat(found.getFirst().registerConsent()).hasValueSatisfying(consent -> assertThat(consent.id()).isEqualTo("71"));
        assertThat(found.getFirst().spex()).extracting(SpexregisterClient.SpexNode::title).containsExactly("Ali Baba");
        server.verify();
    }

    @Test
    void aPendingPersonBecomesAnUnpublishedSpexare() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("spexareCreate")))
                .andExpect(jsonPath("$.variables.input.published").value(false))
                .andExpect(jsonPath("$.variables.input.deceased").value(false))
                .andExpect(header("X-Acting-Person", SpexregisterClient.actingPersonHeader("participant via revival 3")))
                .andRespond(withSuccess("{\"data\": {\"spexareCreate\": {\"id\": \"99\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("addressCreate")))
                .andExpect(jsonPath("$.variables.typeId").value("HOME"))
                .andExpect(jsonPath("$.variables.input.emailAddress").value("pia@example.org"))
                .andRespond(withSuccess("{\"data\": {\"addressCreate\": {\"id\": \"100\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("consentUpdate")))
                .andExpect(jsonPath("$.variables.typeId").value("REGISTER"))
                .andExpect(jsonPath("$.variables.input.id").value("5"))
                .andExpect(jsonPath("$.variables.input.value").value(true))
                .andRespond(withSuccess("{\"data\": {\"consentUpdate\": {\"id\": \"5\"}}}", MediaType.APPLICATION_JSON));

        assertThat(client.createSpexare("Pia", "Persson", "participant via revival 3")).isEqualTo(99L);
        client.saveHomeAddress(99L, null, new SpexregisterClient.AddressInput("pia@example.org", "0701", null, null, null), "participant via revival 3");
        client.saveRegisterConsent(99L, "5", true, "participant via revival 3");
        server.verify();
    }

    @Test
    void tasksTellTheEnsembleByTheirCategory() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"data": {"taskPaged": {"edges": [
                          {"node": {"id": "1", "name": "Roll", "category": {"id": "10", "name": "Ensemble", "actorPresent": true}}},
                          {"node": {"id": "2", "name": "Trumpet", "category": {"id": "11", "name": "Orkester", "actorPresent": false}}}
                        ], "totalCount": 2}}}""", MediaType.APPLICATION_JSON));

        assertThat(client.tasks()).extracting(task -> task.category() != null && Boolean.TRUE.equals(task.category().actorPresent()))
                .containsExactly(true, false);
    }

    @Test
    void anUnknownSpexHasNoParticipants() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"data": {"spexareBySpex": null}, "errors": [{"message": "Not found", "extensions": {"classification": "NOT_FOUND"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.spexareBySpex(5L, ACTING)).isEmpty();
    }

    @Test
    void anErrorIsACallException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.searchSpexare("anna", ACTING)).isInstanceOf(SpexregisterClient.SpexregisterCallException.class);
    }

    @Test
    void theSyncReadsActivitiesAndWritesWithTheActingPerson() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("taskActivities")))
                .andExpect(header("X-Acting-Person", SpexregisterClient.actingPersonHeader(ACTING)))
                .andRespond(withSuccess("""
                        {"data": {"spexareByIds": [{"id": "7", "activities": [{"id": "70", "spexActivity": {"id": "71", "spex": {"id": "500"}},
                          "taskActivities": [{"id": "72", "task": {"id": "ROLE"}, "actors": [{"id": "73", "role": "Kalifen", "vocal": {"id": "B1"}}]}]}]}]}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("actorCreate")))
                .andExpect(jsonPath("$.variables.vocalId").value("B1"))
                .andExpect(jsonPath("$.variables.input.role").value("Kalifen"))
                .andExpect(header("X-Acting-Person", SpexregisterClient.actingPersonHeader(ACTING)))
                .andRespond(withSuccess("{\"data\": {\"actorCreate\": {\"id\": \"73\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.containsString("spexRevivalCreate")))
                .andExpect(jsonPath("$.variables.year").value("2028"))
                .andRespond(withSuccess("{\"data\": {\"spexRevivalCreate\": {\"id\": \"500\", \"year\": \"2028\"}}}", MediaType.APPLICATION_JSON));

        final List<SpexregisterClient.SpexareActivities> found = client.spexareActivities(List.of(7L), ACTING);

        assertThat(found.getFirst().all().getFirst().spexId()).isEqualTo("500");
        assertThat(found.getFirst().all().getFirst().tasks().getFirst().allActors()).extracting(SpexregisterClient.Actor::role).containsExactly("Kalifen");
        assertThat(client.createActor(7L, 70L, 72L, "B1", "Kalifen", ACTING)).isEqualTo(73L);
        assertThat(client.createSpexRevival(1L, 2028, ACTING)).isEqualTo(500L);
        server.verify();
    }

    @Test
    void writeErrorsAreClassified() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.variables.typeId").value("FGV"))
                .andRespond(withSuccess("""
                        {"data": {"membershipCreate": null}, "errors": [{"message": "Exists", "extensions": {"classification": "CONFLICT"}}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"data": {"taskActivityCreate": null}, "errors": [{"message": "No", "extensions": {"classification": "NOT_FOUND"}}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.createMembership(7L, 2028, ACTING)).isInstanceOfSatisfying(SpexregisterClient.SpexregisterCallException.class,
                e -> assertThat(e.code()).isEqualTo(SpexregisterClient.SpexregisterCallException.EXISTS));
        assertThatThrownBy(() -> client.createTaskActivity(7L, 70L, "ROLE", ACTING)).isInstanceOfSatisfying(
                SpexregisterClient.SpexregisterCallException.class, e -> assertThat(e.code()).isEqualTo(SpexregisterClient.SpexregisterCallException.NOT_FOUND));
        assertThatThrownBy(() -> client.createActivity(7L, ACTING)).isInstanceOfSatisfying(SpexregisterClient.SpexregisterCallException.class,
                e -> assertThat(e.code()).isEqualTo(SpexregisterClient.SpexregisterCallException.UNAVAILABLE));
    }
}
