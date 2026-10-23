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

import nu.fgv.revival.server.spex.SpexregisterClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.4
 */
@ExtendWith(MockitoExtension.class)
class RevivalHttpApiTest {

    @Mock
    private RevivalService revivalService;

    @InjectMocks
    private RevivalHttpApi api;

    @Test
    void servesThePosterSandboxed() {
        when(revivalService.poster(1L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1, 2}, "image/svg+xml;charset=UTF-8")));

        final ResponseEntity<byte[]> response = api.poster(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"));
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void noPosterIsNoContent() {
        when(revivalService.poster(1L)).thenReturn(Optional.empty());

        assertThat(api.poster(1L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void anythingButAnImageIsNoPoster() {
        when(revivalService.poster(1L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1}, "text/html")));
        when(revivalService.poster(2L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1}, "not a type")));

        assertThat(api.poster(1L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.poster(2L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
