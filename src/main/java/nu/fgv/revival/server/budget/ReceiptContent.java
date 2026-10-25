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
package nu.fgv.revival.server.budget;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A receipt's file. Not audited: the transaction records every upload and removal, and the file goes when the receipt
 * is replaced or removed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "receipt_content")
@NoArgsConstructor
@Getter
@Setter
public class ReceiptContent {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] content;

    public ReceiptContent(final Long transactionId, final byte[] content) {
        this.transactionId = transactionId;
        this.content = content;
    }
}
