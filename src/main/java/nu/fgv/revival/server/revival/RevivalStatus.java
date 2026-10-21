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

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The lifecycle of a revival (product spec, section 4).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public enum RevivalStatus {

    PROPOSED,
    APPROVED,
    ORGANISING,
    OPEN,
    CLOSING,
    SETTLING,
    ARCHIVED,
    REJECTED;

    /**
     * The steps a directeur may take, one at a time (FR-014).
     */
    private static final List<RevivalStatus> DIRECTEUR_STEPS = List.of(APPROVED, ORGANISING, OPEN, CLOSING, SETTLING);

    /**
     * The next step on {@code APPROVED → ORGANISING → OPEN → CLOSING → SETTLING}, or null when there is none.
     */
    public @Nullable RevivalStatus nextDirecteurStep() {
        final int index = DIRECTEUR_STEPS.indexOf(this);

        return index >= 0 && index < DIRECTEUR_STEPS.size() - 1 ? DIRECTEUR_STEPS.get(index + 1) : null;
    }
}
