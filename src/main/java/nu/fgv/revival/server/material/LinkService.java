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
package nu.fgv.revival.server.material;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.catalog.CatalogEntry;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * A place materials are kept, from the list the super admin keeps (M8-FR-1): one of FGV's applications, such as
 * Spexflix, or a service such as Google Drive. A link belongs to it when the link's host is one of its hosts or a
 * subdomain of one; a host belongs to at most one active service.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
@Entity
@Table(name = "link_service")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class LinkService extends CatalogEntry {

    public static final int MAX_HOSTS = 10;
    public static final int HOST_MAX_LENGTH = 253;
    public static final int HOME_URL_MAX_LENGTH = 500;

    @Column(name = "home_url", length = HOME_URL_MAX_LENGTH)
    private @Nullable String homeUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "link_service_host", joinColumns = @JoinColumn(name = "link_service_id"))
    @Column(name = "host", nullable = false, length = HOST_MAX_LENGTH)
    private Set<String> hosts = new HashSet<>();
}
