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

package nu.fgv.revival.server.guide;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * The guide's read model, {@link GuideVersionView}, already has GraphQL's shape and is built by
 * {@link GuideVersionViews}. This maps the small types several features share.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Mapper
public interface GuideMapper {

    GuideMapper GUIDE_MAPPER = Mappers.getMapper(GuideMapper.class);

    GuideVersionSummaryDto toSummary(GuideVersion version);

    GuideVersionSummaryDto toSummary(GuideVersionView version);
}
