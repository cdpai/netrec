package littlejlib.netrec;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.server.ArcadeDBServer;
import java.nio.file.Files;
import static com.arcadedb.GlobalConfiguration.*;

/** Resident ArcadeDB server: the hub the recorder writes to and readers query over HTTP. */
public final class Hub {
    ArcadeDBServer server;
    int port;

    /** @param portSpec a single port ("2480") or a range ("2480-2489"); the first free port in it is bound. */
    public void start(String portSpec) throws Exception {
        J.portRange(portSpec);
        Files.createDirectories(J.dbDir());
        var cfg = new ContextConfiguration();
        cfg.setValue(SERVER_ROOT_PATH, J.home().toString());
        cfg.setValue(SERVER_ROOT_PASSWORD, J.ROOT_PW);
        cfg.setValue(SERVER_DATABASE_DIRECTORY, J.dbDir().toString());
        cfg.setValue(HA_ENABLED, false);
        cfg.setValue(SERVER_HTTP_INCOMING_HOST, "127.0.0.1");
        cfg.setValue(SERVER_HTTP_INCOMING_PORT, portSpec);
        server = new ArcadeDBServer(cfg);
        try { server.start(); }
        catch (Exception e) { throw startFailed(portSpec, e); }
        port = server.getHttpServer().getPort();
        ensureSchema(server.getOrCreateDatabase(J.DB_NAME));
        removeStrayLogDir();
        System.err.println("[netrec] hub on http://127.0.0.1:" + port + "  db=" + J.dbDir().resolve(J.DB_NAME));
    }

    /** The port actually bound (0 before start): ArcadeDB picks the first free one in the range. */
    public int httpPort() { return port; }

    /**
     * A busy port range is the one start failure with an obvious remedy, and ArcadeDB reports it as a bare
     * "Unable to listen ..." -- say which knob fixes it. The half-started server is stopped so the DB lock goes.
     */
    Exception startFailed(String portSpec, Exception cause) {
        try { stop(); } catch (Exception ignore) {}
        for (Throwable t = cause; t != null; t = t.getCause())
            if (String.valueOf(t.getMessage()).contains("Unable to listen"))
                return new IllegalStateException("the hub found no free HTTP port in " + portSpec
                    + " -- every port there is held by another process (other ArcadeDB apps default to 2480 too)."
                    + "  Pick a free range:  netrec config --hub-port 2600-2609"
                    + "  (or once:  netrec serve --port 2600-2609)", cause);
        return cause;
    }

    void ensureSchema(Database db) {
        var t = db.getSchema().getOrCreateDocumentType("ReqRec");
        t.getOrCreateProperty("id", Type.STRING);
        t.getOrCreateProperty("session", Type.STRING);
        t.getOrCreateProperty("host", Type.STRING);
        t.getOrCreateProperty("ts", Type.LONG);
        t.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id");
        t.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "session");
        t.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "host");
        t.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "ts");
        var m = db.getSchema().getOrCreateDocumentType("Mark");
        m.getOrCreateProperty("ts", Type.LONG);
        m.getOrCreateProperty("label", Type.STRING);
        m.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "ts");
    }

    public Database db() { return server.getDatabase(J.DB_NAME); }

    /**
     * Drop the database files and start an empty one: the only way to get stored cookies off the disk for real.
     * The shared server wrapper refuses drop(), so it goes through the embedded instance, and the directory is
     * then checked -- callers report "scrubbed" only when the bytes are really gone.
     */
    public Database recreate() {
        server.getDatabase(J.DB_NAME).getEmbedded().drop();
        try { server.removeDatabase(J.DB_NAME); } catch (Exception ignore) {}
        var fresh = server.getOrCreateDatabase(J.DB_NAME);
        ensureSchema(fresh);
        System.err.println("[netrec] store wiped and recreated: " + J.dbDir().resolve(J.DB_NAME));
        return fresh;
    }

    /**
     * Bytes still on disk that predate the wipe (0 = a real scrub). File names cannot be used for this --
     * a fresh database recreates the same names -- so it goes by modification time.
     */
    public long staleBytes(long wipedAt) {
        var dir = J.dbDir().resolve(J.DB_NAME);
        try (var files = Files.list(dir)) {
            return files.filter(f -> {
                try { return Files.getLastModifiedTime(f).toMillis() < wipedAt; } catch (Exception e) { return false; }
            }).mapToLong(f -> { try { return Files.size(f); } catch (Exception e) { return 0; } }).sum();
        } catch (Exception e) { return -1; }
    }

    public void stop() { if (server != null && server.isStarted()) server.stop(); }

    /** ArcadeDB's logger hardcodes mkdir("./log") in the launch dir; its real logs go under home, so drop the empty stray. */
    void removeStrayLogDir() {
        var d = new java.io.File("log");
        var files = d.list();
        if (d.isDirectory() && files != null && files.length == 0) d.delete();
    }
}

