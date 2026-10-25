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
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

/**
 * A revival's plain HTTP endpoints, for what GraphQL does not suit: the spex poster as an image.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class RevivalHttpApi {

    /**
     * The image types Spexregister accepts for posters; anything else is treated as no poster.
     */
    static final Set<String> POSTER_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_GIF_VALUE, "image/svg+xml", "image/webp");

    private final RevivalService revivalService;

    public RevivalHttpApi(final RevivalService revivalService) {
        this.revivalService = revivalService;
    }

    private static ResponseEntity<byte[]> image(final SpexregisterClient.Poster poster) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(poster.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(poster.content());
    }

    private static String baseType(final String contentType) {
        try {
            final MediaType type = MediaType.parseMediaType(contentType);

            return type.getType() + "/" + type.getSubtype();
        } catch (final InvalidMediaTypeException _) {
            return "";
        }
    }

    /**
     * 200 with the image, 204 when the spex has no poster, 404 when the revival is missing or not visible and 503 when
     * Spexregister is unavailable. The image is sandboxed, since an SVG opened on its own could otherwise run script.
     */
    @GetMapping("/api/revivals/{id}/poster")
    public ResponseEntity<byte[]> poster(@PathVariable final Long id) {
        return revivalService.poster(id)
                .filter(poster -> POSTER_TYPES.contains(baseType(poster.contentType())))
                .map(RevivalHttpApi::image)
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).build());
    }
}
