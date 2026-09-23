package cdpai.netrec;

import cdpai.gate.client.ScopeRequest;

/**
 * Which browser connection to use, and how to reach it. Gate mode is the default and preferred
 * path (no port, no unauthenticated listener); direct mode is the legacy fallback, kept for a
 * browser cdpgate does not own -- explicit opt-in only (`--direct`, or a `--port` override, which
 * implies it). `scope` only matters in gate mode -- a narrower request earns a longer, safer
 * approval from cdpgate; direct mode has no such concept, so it always carries UNSCOPED.
 */
public record ConnSpec(boolean gate, int port, String pipeName, String keyName, ScopeRequest scope) {

    public static ConnSpec resolve(Settings s, Integer portOverride, boolean direct) {
        return resolve(s, portOverride, direct, ScopeRequest.UNSCOPED);
    }

    public static ConnSpec resolve(Settings s, Integer portOverride, boolean direct, ScopeRequest scope) {
        if (portOverride != null || direct) return direct(portOverride != null ? portOverride : s.cdpPort());
        return Settings.MODE_DIRECT.equals(s.cdpMode()) ? direct(s.cdpPort()) : gate(s.gatePipe(), s.gateKey(), scope);
    }

    public static ConnSpec gate(String pipeName, String keyName, ScopeRequest scope) { return new ConnSpec(true, 0, pipeName, keyName, scope); }

    public static ConnSpec direct(int port) { return new ConnSpec(false, port, null, null, ScopeRequest.UNSCOPED); }

    /** Identifies WHICH connection this is, for "already attached elsewhere" comparisons and display. */
    public String key() { return gate ? "gate:" + pipeName : "direct:" + port; }

    public CdpLink open() { return gate ? new GateCdpLink(pipeName, keyName, scope) : new CdpConn(port); }
}
