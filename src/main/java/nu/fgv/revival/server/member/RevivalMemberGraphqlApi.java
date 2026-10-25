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

package nu.fgv.revival.server.member;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * No authorization here: the service decides.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class RevivalMemberGraphqlApi {

    private final RevivalMemberService service;

    public RevivalMemberGraphqlApi(final RevivalMemberService service) {
        this.service = service;
    }

    @QueryMapping
    public List<MemberCandidateDto> revivalMemberCandidates(@Argument final Long revivalId, @Argument final String query) {
        return service.candidates(revivalId, query);
    }

    @MutationMapping
    public RevivalMemberDto revivalMemberAdd(@Argument final RevivalMemberAddDto input) {
        return service.add(input);
    }

    @MutationMapping
    public RevivalMemberDto revivalMemberCapabilitiesSet(@Argument final RevivalMemberCapabilitiesDto input) {
        return service.setCapabilities(input);
    }

    @MutationMapping
    public void revivalMemberRemove(@Argument final Long id) {
        service.remove(id);
    }

}
