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

package nu.fgv.revival.server.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.scalars.ExtendedScalars;
import nu.fgv.revival.server.util.graphql.CustomScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.util.regex.Pattern;

/**
 * Scalars and query limits.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Configuration
public class GraphqlConfig {

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.Date)
                .scalar(
                        ExtendedScalars.newRegexScalar("Email")
                                .addPattern(Pattern.compile("^(.+)@(\\S+)$"))
                                .build()
                )
                .scalar(
                        ExtendedScalars.newAliasedScalar("Year")
                                .aliasedScalar(ExtendedScalars.GraphQLShort)
                                .build()
                )
                .scalar(CustomScalars.INSTANT)
                .scalar(CustomScalars.AMOUNT)
                .scalar(CustomScalars.VOID);
    }

    @Bean
    public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(10);
    }

    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(500);
    }

}
