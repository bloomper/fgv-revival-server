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

package nu.fgv.revival.server.audit;

import nu.fgv.revival.server.util.security.SecurityUtil;
import org.hibernate.envers.RevisionListener;

/**
 * Fills in who made the revision and where it came from.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public class AuditRevisionListener implements RevisionListener {

    @Override
    public void newRevision(final Object revisionEntity) {
        final AuditRevisionEntity rev = (AuditRevisionEntity) revisionEntity;
        final AuditContext.Origin origin = AuditContext.currentOrSystem();

        rev.setModifiedBy(SecurityUtil.currentSubjectOrSystem());
        rev.setSource(origin.source());
        rev.setOperation(origin.operation());
        rev.setComment(origin.comment());
    }
}
