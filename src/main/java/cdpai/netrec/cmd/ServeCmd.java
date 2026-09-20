package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "serve", description = "Run the daemon in the foreground: holds the DB, serves the ctl API, "
    + "owns the recorder. Any other netrec command starts one of these on demand.")
public final class ServeCmd implements Callable<Integer> {
    @Option(names = "--idle", defaultValue = "0",
        description = "Shut down after N minutes with no ctl traffic and nothing recording (0 = never).") int idle;
    @Option(names = { "--http-port", "--port" }, paramLabel = "<port|range>",
        description = "Hub (ArcadeDB) HTTP port or range for this run, e.g. 2600 or 2600-2609. "
            + "Default: hub.port from config, else " + J.HUB_PORT_DEFAULT
            + ". To make it stick:  netrec config --hub-port <port|range>.") String httpPort;

    public Integer call() throws Exception {
        var ctl = new Ctl();
        var hub = new Hub();
        hub.start(httpPort != null ? httpPort : Settings.load().hubPort());
        var api = new CtlApi(hub, ctl);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { api.closeQuietly(); hub.stop(); }));
        api.wire(idle);
        System.err.println("[netrec] ctl on http://127.0.0.1:" + J.CTL_PORT + "  pid=" + ProcessHandle.current().pid()
            + (idle > 0 ? "  idle-timeout=" + idle + "m" : ""));
        System.err.println("[netrec] ready -- netrec rec --tab <x> to attach, netrec stop to shut down cleanly");
        new CountDownLatch(1).await();
        return 0;
    }
}
