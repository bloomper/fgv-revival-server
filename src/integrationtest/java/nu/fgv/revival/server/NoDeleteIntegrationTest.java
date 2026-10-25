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


package nu.fgv.revival.server;

import graphql.schema.GraphQLFieldDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.graphql.execution.GraphQlSource;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing in the decision log, meetings, checklists, events, budgets, materials or inventory can be deleted, by any
 * route.
 *
 * @author Anders Jacobsson
 */
class NoDeleteIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> M3_PREFIXES = List.of("logEntry", "meeting", "minutes", "eventChecklist", "checklist", "event",
            "calendarFeed");
    private static final List<String> M3_PACKAGES = List.of("decisionlog", "meeting", "checklist", "event", "calendar");
    /**
     * The lines event types propose are the super admin's, not revival data: removing one deletes it.
     */
    private static final String EVENT_TYPE_BUDGET_LINES = "eventTypeBudgetLine";
    private static final List<String> BUDGET_PREFIXES = List.of("budgetSettings", "budgetLine", "transaction", "budgetScenario");
    /**
     * A revival's budget rows; a receipt's file goes when the receipt is replaced or removed, and the catalogs and
     * reference figures are not revival data.
     */
    private static final List<String> BUDGET_REPOSITORIES = List.of("BudgetSettingsRepository", "BudgetLineRepository",
            "BudgetTransactionRepository", "BudgetScenarioRepository");
    /**
     * The one exception: the guide sync deletes a revival's checklist for a chapter, with its items, when the revival
     * moves to a guide version without that chapter.
     */
    private static final Map<String, List<String>> GUIDE_SYNC_DELETES = Map.of(
            "ChecklistRepository", List.of("delete"),
            "ChecklistItemRepository", List.of("deleteAll"));

    @Autowired
    private GraphQlSource graphQlSource;

    @Test
    void noM3MutationDeletesOrRemoves() {
        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> M3_PREFIXES.stream().anyMatch(name::startsWith) && !name.startsWith(EVENT_TYPE_BUDGET_LINES))
                .toList();

        assertThat(mutations).isNotEmpty().noneMatch(name -> name.contains("Delete") || name.contains("Remove"));
    }

    /**
     * Budget lines and scenarios are removed by marking them, which the GraphQL tests show; nothing deletes them.
     */
    @Test
    void noBudgetMutationDeletes() {
        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> BUDGET_PREFIXES.stream().anyMatch(name::startsWith))
                .toList();

        assertThat(mutations).contains("budgetLineRemove", "transactionCancel", "budgetScenarioRemove").noneMatch(name -> name.contains("Delete"));
    }

    @Test
    void noBudgetRepositoryCanDelete() throws ClassNotFoundException {
        for (final String name : BUDGET_REPOSITORIES) {
            final Class<?> repository = Class.forName("nu.fgv.revival.server.budget." + name);

            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(JpaSpecificationExecutor.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName)).as(name)
                    .noneMatch(method -> method.startsWith("delete") || method.startsWith("remove"));
        }
    }

    /**
     * Participants and their settings are removed by closing them; only data whose home is this application and
     * that must leave no trace (pending people, dietary needs, handled referrals) is deleted.
     */
    @Test
    void participantsAndTheirSettingsCannotBeDeleted() throws ClassNotFoundException {
        for (final String name : List.of("ParticipantRepository", "ParticipationSettingsRepository")) {
            final Class<?> repository = Class.forName("nu.fgv.revival.server.participant." + name);

            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName)).as(name)
                    .noneMatch(method -> method.startsWith("delete") || method.startsWith("remove"));
        }

        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> name.startsWith("participant") || name.startsWith("participation"))
                .toList();

        assertThat(mutations).contains("participantRemove").noneMatch(name -> name.contains("Delete"));
    }

    /**
     * Mailings are cancelled and their recipients kept, never deleted; the mailing plan's templates are retired. A
     * shift slot is removed only while nobody holds it, as an empty planning row, which the staffing tests show.
     */
    @Test
    void mailingsAndTheirTemplatesCannotBeDeleted() throws ClassNotFoundException {
        for (final String name : List.of("MailingRepository", "MailingRecipientRepository", "MailingTemplateRepository")) {
            final Class<?> repository = Class.forName("nu.fgv.revival.server.mailing." + name);

            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName)).as(name)
                    .noneMatch(method -> method.startsWith("delete") || method.startsWith("remove"));
        }

        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> name.startsWith("mailing") || name.startsWith("shift"))
                .toList();

        assertThat(mutations).contains("mailingCancel", "mailingTemplateRetire", "shiftSlotRemove").noneMatch(name -> name.contains("Delete"))
                .filteredOn(name -> name.contains("Remove")).containsExactly("shiftSlotRemove");
    }

    /**
     * Materials and inventory items are removed by marking them; the link services are the super admin's list, and
     * only one nothing holds is deleted.
     */
    @Test
    void materialsAndInventoryCannotBeDeleted() throws ClassNotFoundException {
        for (final String name : List.of("MaterialRepository", "InventoryItemRepository")) {
            final Class<?> repository = Class.forName("nu.fgv.revival.server.material." + name);

            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(JpaSpecificationExecutor.class.isAssignableFrom(repository)).as(name).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName)).as(name)
                    .noneMatch(method -> method.startsWith("delete") || method.startsWith("remove"));
        }

        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> name.startsWith("material") || name.startsWith("inventory"))
                .toList();

        assertThat(mutations).contains("materialRemove", "inventoryItemRemove").noneMatch(name -> name.contains("Delete"));
    }

    @Test
    void noMcpToolOffersDeletion() {
        login(STEERING_GROUP);

        final JsonNode tools = mcpRpc(mcpToken(STEERING_GROUP), "tools/list", Map.of()).at("/result/tools");
        final List<String> offered = new ArrayList<>();

        tools.forEach(tool -> offered.add(tool.get("name").asString() + " " + tool.get("description").asString()));
        // Deleting is mentioned only to say it does not happen
        assertThat(offered).hasSizeGreaterThanOrEqualTo(6)
                .noneMatch(text -> text.toLowerCase().replace("never deleted", "").replace("nothing is deleted", "").matches(".*\\bdelet\\w*.*"));
        // Removing is marking, as in the web: the tools that do it say nothing is deleted
        assertThat(offered).filteredOn(text -> text.split(" ")[0].contains("remove"))
                .isNotEmpty()
                .allMatch(text -> text.toLowerCase().contains("nothing is deleted"));
    }

    @Test
    void noM3RepositoryCanDelete() throws ClassNotFoundException {
        final ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(final AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        final List<Class<?>> repositories = new ArrayList<>();

        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        for (final String name : M3_PACKAGES) {
            for (final BeanDefinition definition : scanner.findCandidateComponents("nu.fgv.revival.server." + name)) {
                repositories.add(Class.forName(Objects.requireNonNull(definition.getBeanClassName())));
            }
        }

        assertThat(repositories).extracting(Class::getSimpleName)
                .contains("LogEntryRepository", "MeetingRepository", "ChecklistItemRepository", "ChecklistTemplateRepository", "EventRepository",
                        "EventTypeRepository", "CalendarFeedRepository");
        for (final Class<?> repository : repositories) {
            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(repository.getSimpleName()).isFalse();
            assertThat(JpaSpecificationExecutor.class.isAssignableFrom(repository)).as(repository.getSimpleName()).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName)
                    .filter(name -> !GUIDE_SYNC_DELETES.getOrDefault(repository.getSimpleName(), List.of()).contains(name)))
                    .as(repository.getSimpleName())
                    .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
        }
    }
}
