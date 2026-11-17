package dev.kmads.consignado.gateway.adapters;

import dev.kmads.consignado.gateway.ports.SnapshotStore;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** The blob data/snapshot.json through the Blob REST API with a managed identity token. No storage key. */
public final class BlobSnapshotStore implements SnapshotStore {
    private static final String VERSION = "2023-11-03";

    private final AzureTokens tokens;
    private final URI uri;

    public BlobSnapshotStore(AzureTokens tokens, String storageAccount) {
        this.tokens = tokens;
        this.uri = URI.create("https://" + storageAccount + ".blob.core.windows.net/data/snapshot.json");
    }

    @Override
    public Optional<String> read() {
        try {
            HttpRequest request = base().GET().build();
            HttpResponse<String> response = Http.CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IllegalStateException("blob read HTTP " + response.statusCode());
            return Optional.of(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("blob read failed");
        }
    }

    @Override
    public void write(String json) {
        try {
            HttpRequest request = base()
                    .header("x-ms-blob-type", "BlockBlob")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 201) throw new IllegalStateException("blob write HTTP " + response.statusCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("blob write failed");
        }
    }

    private HttpRequest.Builder base() {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + tokens.token(AzureTokens.STORAGE_SCOPE))
                .header("x-ms-version", VERSION);
    }
}
