package io.github.itsmeck24.fineprint.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// maps a pasted job link to (ats, board token, posting id). the parts are checked against strict patterns
// because they end up in request paths to the job board apis.
public final class PostingUrls {

    private PostingUrls() {
    }

    public record Parsed(String ats, String token, String externalId) {
        public boolean boardKnown() {
            return token != null;
        }
    }

    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    static final Pattern GH_ID = Pattern.compile("\\d{1,20}");
    static final Pattern LEVER_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern GH_PATH = Pattern.compile("^/([^/]+)/jobs/(\\d+)/?$");
    static final Pattern LEVER_PATH = Pattern.compile("^/([^/]+)/([^/]+?)(/apply)?/?$");
    static final Pattern GH_JID = Pattern.compile("(?:^|&)gh_jid=(\\d{1,20})(?:&|$)");
    static final Pattern EMBED_FOR = Pattern.compile("(?:^|&)for=([A-Za-z0-9_-]{1,100})(?:&|$)");
    static final Pattern EMBED_TOKEN = Pattern.compile("(?:^|&)token=(\\d{1,20})(?:&|$)");

    public static Optional<Parsed> parse(String url) {
        if (url == null || url.length() > 2000) {
            return Optional.empty();
        }
        URI u;
        try {
            u = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String path = u.getPath() == null ? "" : u.getPath();
        String query = u.getRawQuery() == null ? "" : u.getRawQuery();
        if (!"https".equalsIgnoreCase(u.getScheme()) && !"http".equalsIgnoreCase(u.getScheme())) {
            return Optional.empty();
        }

        if (host.equals("job-boards.greenhouse.io") || host.equals("boards.greenhouse.io")
                || host.equals("job-boards.eu.greenhouse.io")) {
            Matcher m = GH_PATH.matcher(path);
            if (m.matches() && TOKEN.matcher(m.group(1)).matches()) {
                return Optional.of(new Parsed("greenhouse", m.group(1), m.group(2)));
            }
            Matcher f = EMBED_FOR.matcher(query);
            Matcher t = EMBED_TOKEN.matcher(query);
            if (path.startsWith("/embed/job_app") && f.find() && t.find()) {
                return Optional.of(new Parsed("greenhouse", f.group(1), t.group(1)));
            }
        }
        if (host.equals("jobs.lever.co") || host.equals("jobs.eu.lever.co")) {
            Matcher m = LEVER_PATH.matcher(path);
            if (m.matches() && TOKEN.matcher(m.group(1)).matches() && LEVER_ID.matcher(m.group(2)).matches()) {
                return Optional.of(new Parsed("lever", m.group(1), m.group(2)));
            }
        }
        // a company careers page that embeds greenhouse: we only learn the posting id
        Matcher j = GH_JID.matcher(query);
        if (j.find() && GH_ID.matcher(j.group(1)).matches()) {
            return Optional.of(new Parsed("greenhouse", null, j.group(1)));
        }
        return Optional.empty();
    }
}
