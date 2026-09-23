package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/// The one thing RecMgr/Capture/Cookies actually need from a CDP connection, factored out so the
/// browser can be reached either directly (a websocket to a debug port, `CdpConn` -- the legacy,
/// discouraged fallback) or through cdpgate (`GateCdpLink`, the default). Same flat-session
/// command/event shape either way; the difference is entirely in how the bytes get to the browser.
public interface CdpLink extends AutoCloseable {

    CompletableFuture<JsonNode> send(String sessionId, String method, ObjectNode params);

    JsonNode call(String sessionId, String method, ObjectNode params);

    void onEvent(BiConsumer<String, JsonNode> listener);

    boolean alive();

    @Override void close();
}
