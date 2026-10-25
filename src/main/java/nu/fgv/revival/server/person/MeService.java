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

package nu.fgv.revival.server.person;

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Capability;
import nu.fgv.revival.server.member.CapabilityRepository;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.member.RevivalRoleAssignmentDto;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import static nu.fgv.revival.server.person.MeMapper.ME_MAPPER;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MeService {

    private final CallerService callerService;
    private final RevivalRepository revivalRepository;
    private final AccessPolicy accessPolicy;
    private final CapabilityRepository capabilityRepository;

    public MeService(final CallerService callerService, final RevivalRepository revivalRepository, final AccessPolicy accessPolicy,
                     final CapabilityRepository capabilityRepository) {
        this.callerService = callerService;
        this.revivalRepository = revivalRepository;
        this.accessPolicy = accessPolicy;
        this.capabilityRepository = capabilityRepository;
    }

    /**
     * The caller, with revival roles only for revivals they can currently see. A directeur holds every capability that
     * is not retired.
     */
    @Transactional(readOnly = true)
    public MeDto me() {
        final Caller caller = callerService.current();
        final Supplier<List<String>> all = () -> capabilityRepository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(capability -> !capability.isRetired())
                .map(Capability::getId)
                .toList();
        final List<RevivalRoleAssignmentDto> roles = revivalRepository.findAllById(caller.revivalRoles().keySet()).stream()
                .filter(revival -> accessPolicy.canSee(caller, revival))
                .sorted(Comparator.comparing(Revival::getId))
                .map(revival -> {
                    final RevivalRoleAssignment assignment = caller.revivalRoles().get(revival.getId());

                    return new RevivalRoleAssignmentDto(revival.getId(), assignment.role(),
                            assignment.holdsAll() ? all.get() : assignment.capabilities().stream().sorted().toList());
                })
                .toList();

        return ME_MAPPER.toDto(caller.person(), caller.globalRoles().stream().sorted().toList(), roles);
    }
}
