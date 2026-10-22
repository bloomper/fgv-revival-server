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
 * Nothing in the decision log, meetings or checklists can be deleted, by any route (M3 SC-005, constitution IV).
 */
class NoDeleteIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> M3_PREFIXES = List.of("logEntry", "logImport", "meeting", "minutes", "eventChecklist", "checklist");
    private static final List<String> M3_PACKAGES = List.of("decisionlog", "meeting", "checklist");

    @Autowired
    private GraphQlSource graphQlSource;

    @Test
    void noM3MutationDeletesOrRemoves() {
        final List<String> mutations = graphQlSource.schema().getMutationType().getFieldDefinitions().stream()
                .map(GraphQLFieldDefinition::getName)
                .filter(name -> M3_PREFIXES.stream().anyMatch(name::startsWith))
                .toList();

        assertThat(mutations).isNotEmpty().noneMatch(name -> name.contains("Delete") || name.contains("Remove"));
    }

    @Test
    void noMcpToolOffersDeletion() {
        login(STEERING_GROUP);

        final JsonNode tools = mcpRpc(mcpToken(STEERING_GROUP), "tools/list", Map.of()).at("/result/tools");
        final List<String> offered = new ArrayList<>();

        tools.forEach(tool -> offered.add(tool.get("name").asString() + " " + tool.get("description").asString()
                .replace("never deleted", "").replace("No tool deletes", "")));
        assertThat(offered).hasSizeGreaterThanOrEqualTo(6).noneMatch(text -> text.toLowerCase().matches(".*\\b(delete|remove)\\w*.*"));
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
                .contains("LogEntryRepository", "MeetingRepository", "ChecklistItemRepository", "ChecklistTemplateRepository");
        for (final Class<?> repository : repositories) {
            assertThat(CrudRepository.class.isAssignableFrom(repository)).as(repository.getSimpleName()).isFalse();
            assertThat(JpaSpecificationExecutor.class.isAssignableFrom(repository)).as(repository.getSimpleName()).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                    .as(repository.getSimpleName())
                    .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
        }
    }
}
