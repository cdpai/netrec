package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "stop", description = "Shut the daemon down gracefully so the DB closes properly "
    + "(killing it instead is what causes 'not closed properly' recovery on the next start).")
public final class StopCmd implements Callable<Integer> {
    public Integer call() {
        if (!Daemon.stop()) { System.out.println("no daemon running"); return 0; }
        System.out.println("daemon stopped");
        return 0;
    }
}
