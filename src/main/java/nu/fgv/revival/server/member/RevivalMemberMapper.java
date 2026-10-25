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

import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@Mapper
public interface RevivalMemberMapper {

    RevivalMemberMapper REVIVAL_MEMBER_MAPPER = Mappers.getMapper(RevivalMemberMapper.class);

    @Mapping(target = "id", source = "member.id")
    @Mapping(target = "role", source = "member.role")
    @Mapping(target = "capabilities", source = "member.capabilities")
    @Mapping(target = "state", source = "member.state")
    @Mapping(target = "invitedAt", source = "member.invitedAt")
    @Mapping(target = "version", source = "member.version")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "email", source = "email")
    RevivalMemberDto toDto(RevivalMember member, @Nullable String displayName, @Nullable String email);
}
