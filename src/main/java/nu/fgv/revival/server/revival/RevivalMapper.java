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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.member.RevivalPermissionsDto;
import nu.fgv.revival.server.spex.SpexDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 0.1
 */
@Mapper
public interface RevivalMapper {

    RevivalMapper REVIVAL_MAPPER = Mappers.getMapper(RevivalMapper.class);

    @Mapping(target = "id", source = "revival.id")
    @Mapping(target = "year", source = "revival.year")
    @Mapping(target = "premiereDate", source = "revival.premiereDate")
    @Mapping(target = "shows", source = "revival.shows")
    @Mapping(target = "status", source = "revival.status")
    @Mapping(target = "guideVersionId", source = "revival.guideVersionId")
    @Mapping(target = "version", source = "revival.version")
    @Mapping(target = "createdBy", source = "revival.createdBy")
    @Mapping(target = "createdAt", source = "revival.createdAt")
    @Mapping(target = "lastModifiedBy", source = "revival.lastModifiedBy")
    @Mapping(target = "lastModifiedAt", source = "revival.lastModifiedAt")
    @Mapping(target = "spex", source = "spex")
    @Mapping(target = "permissions", source = "permissions")
    RevivalDto toDto(Revival revival, SpexDto spex, RevivalPermissionsDto permissions);

    /**
     * Ordered by date, then venue, whatever order they were loaded in.
     */
    default List<RevivalShowDto> toShowDtos(final List<RevivalShow> shows) {
        return shows.stream().map(RevivalShow::toDto).sorted(RevivalShowDto.ORDER).toList();
    }

    @Mapping(target = "from", source = "fromStatus")
    @Mapping(target = "to", source = "toStatus")
    RevivalStatusChangeHistoryDto toHistoryDto(RevivalStatusChange change);
}
