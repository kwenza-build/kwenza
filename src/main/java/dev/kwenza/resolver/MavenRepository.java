package dev.kwenza.resolver;

import dev.kwenza.context.Result;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A remote Maven-layout repository, over HTTP, and nothing else: no local cache and no checksum
 * policy (both are {@link ArtifactStore}'s). A failed fetch (a 404, no network) is an expected
 * outcome, so it's a failed Result naming the URL, never an exception.
 *
 * <p>The HTTP client is created on the first fetch, not up front: a build that resolves from a
 * fresh lock with every jar already cached never needs one, and creating one loads a few hundred
 * TLS classes (see AGENTS.md, AppCDS notes).
 */
public final class MavenRepository {
    public static final String CENTRAL_BASE_URL = "https://repo.maven.apache.org/maven2";

    private final String baseUrl;
    private HttpClient client;

    public MavenRepository(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static MavenRepository central() {
        return new MavenRepository(CENTRAL_BASE_URL);
    }

    /** Where {@code gav}'s file with this extension lives in the repository. */
    public String url(Gav gav, String extension) {
        return baseUrl + "/" + gav.group().replace('.', '/') + "/" + gav.artifact() + "/" + gav.version() + "/"
                + gav.artifact() + "-" + gav.version() + "." + extension;
    }

    /** The file's bytes, or a failure saying which URL couldn't be fetched and why. */
    public CompletableFuture<Result<byte[]>> fetch(Gav gav, String extension) {
        String url = url(gav, extension);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).handle((response, ex) -> {
            if (ex != null) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                return Result.err("Failed to fetch " + url + " (" + cause + ")");
            }
            if (response.statusCode() != 200) {
                return Result.err("Failed to fetch " + url + " (HTTP " + response.statusCode() + ")");
            }
            return Result.ok(response.body());
        });
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }
}
