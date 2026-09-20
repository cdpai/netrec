package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "cookies", description = "Read the browser's cookie jar over CDP -- no traffic needed, so an auth "
    + "cookie can be recovered without asking anyone to reload a page. Values are masked unless --reveal.",
    footerHeading = "%nTypical use:%n",
    footer = {
        "  netrec cookies --url portal.example.com            what is held, and when it expires",
        "  netrec cookies --url portal.example.com --name _oauth2_proxy --value-only",
        "      the bare value on stdout, for piping straight into a tool that stores it" })
public final class CookiesCmd implements Callable<Integer> {
    @Option(names = "--url", description = "Only cookies the browser would send to this site (host or full url).") String url;
    @Option(names = "--domain", description = "Only cookies whose domain contains this substring.") String domain;
    @Option(names = { "-n", "--name" }, description = "Only cookies whose name contains this substring.") String name;
    @Option(names = "--reveal", description = "Print real values instead of <redacted>.") boolean reveal;
    @Option(names = "--value-only", description = "Print just the value of the single matching cookie, nothing else "
        + "(implies --reveal; fails unless exactly one cookie matches, so a script can never grab the wrong one).") boolean valueOnly;
    @Option(names = "--port", description = "CDP debug port. Default: the per-install port from `netrec config`.") Integer port;
    @Option(names = "--json", description = "Output JSON instead of a table.") boolean json;

    public Integer call() {
        var body = J.obj().put("reveal", reveal || valueOnly);
        if (url != null) body.put("url", url);
        if (domain != null) body.put("domain", domain);
        if (name != null) body.put("name", name);
        if (port != null) body.put("port", port);
        var r = Api.call("/cookies", body, true);
        var cookies = r.path("cookies");

        if (valueOnly) {
            if (cookies.size() != 1) {
                System.err.println("--value-only needs exactly one match, got " + cookies.size()
                    + " -- narrow it with --url / --name" + (cookies.size() > 1 ? ":" : ""));
                for (var c : cookies) System.err.println("  " + c.path("name").asText() + "  " + c.path("domain").asText());
                return 1;
            }
            System.out.println(cookies.get(0).path("value").asText());
            return 0;
        }

        if (json) { System.out.println(J.pretty(r)); return 0; }
        if (cookies.size() == 0) {
            System.err.println("no cookie matches (jar holds " + r.path("total").asInt() + ") -- "
                + "check the site is actually logged in, and that this is the right browser profile on cdp port "
                + r.path("cdpPort").asInt());
            return 1;
        }
        for (var c : cookies) {
            var exp = c.path("expires").asText();
            var life = c.path("expired").asBoolean(false) ? "EXPIRED " + c.path("expiredAgo").asText()
                     : c.has("expiresIn") ? "in " + c.path("expiresIn").asText() : "";
            System.out.printf("%-28s %-26s %-20s %-16s %s%n", c.path("name").asText(), c.path("domain").asText(),
                exp, life, c.path("value").asText());
        }
        if (!reveal) System.err.println("[netrec] values masked -- add --reveal, or --value-only to pipe one out");
        return 0;
    }
}
