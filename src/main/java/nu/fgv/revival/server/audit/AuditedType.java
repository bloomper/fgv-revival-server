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

/**
 * The records whose history can be read. The others are audited as well, but their history is not shown.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum AuditedType {

    LOG_ENTRY,
    CHECKLIST_ITEM,
    EVENT,
    BUDGET_LINE,
    BUDGET_TRANSACTION,
    MATERIAL,
    NEWS
}
