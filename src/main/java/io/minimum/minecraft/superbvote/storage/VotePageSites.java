package io.minimum.minecraft.superbvote.storage;

import io.minimum.minecraft.superbvote.SuperbVote;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts the vote sites listed on the website's vote page (vote-page.url), so {@code daily-votes: sites-2} follows
 * the page when a site is added or removed. The last count read is kept in vote_page_sites.txt, so a restart while
 * the page can't be reached keeps it; before the first read, vote-page.fallback-sites is used.
 */
public class VotePageSites {
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"'](https?://[^\"'\\s>]+)[\"']", Pattern.CASE_INSENSITIVE);
    // The vote page's list of links (catcraft.net/vote); the whole page is read if it isn't there.
    private static final String LINKS_ID = "catcraft-vote-links";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final File saveTo;
    private volatile int sites;

    public VotePageSites(File saveTo) {
        this.saveTo = saveTo;
        this.sites = readSaved();
    }

    /** Sites on the vote page: the last count read, else vote-page.fallback-sites. */
    public int getSites() {
        int count = sites;
        return count > 0 ? count : SuperbVote.getPlugin().getConfig().getInt("vote-page.fallback-sites", 10);
    }

    /** Reads the page and updates the count. Blocking (web request): call off the main thread. */
    public void refresh() {
        String url = SuperbVote.getPlugin().getConfig().getString("vote-page.url", "");
        if (url == null || url.trim().isEmpty()) return;
        try {
            int count = count(fetch(url.trim()), host(url.trim()));
            if (count <= 0) {
                SuperbVote.getPlugin().getLogger().warning("Found no vote links on " + url + "; keeping " + getSites() + " sites.");
                return;
            }
            int before = sites;
            sites = count;
            if (count != before) {
                SuperbVote.getPlugin().getLogger().info("Vote page lists " + count + " sites" + (before > 0 ? " (was " + before + ")." : "."));
                Files.write(saveTo.toPath(), String.valueOf(count).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            SuperbVote.getPlugin().getLogger().warning("Couldn't read the vote page " + url + " (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "); keeping " + getSites() + " sites.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SuperbVote vote-page")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }

    /** Distinct sites (by host) linked from the vote list, ignoring links back to the page's own site. */
    static int count(String html, String ownHost) {
        int start = html.indexOf("id=\"" + LINKS_ID + "\"");
        if (start >= 0) {
            int end = html.indexOf("</ul>", start);
            html = html.substring(start, end > start ? end : html.length());
        }
        boolean wholePage = start < 0;
        Set<String> hosts = new LinkedHashSet<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String url = m.group(1).replace("&amp;", "&");
            String host = host(url);
            if (host.isEmpty() || host.equals(ownHost)) continue;
            if (wholePage && !url.toLowerCase(Locale.ROOT).contains("vote")) continue;
            hosts.add(host);
        }
        return hosts.size();
    }

    /** Lower-case host without "www.", or "" if the address can't be parsed. */
    static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private int readSaved() {
        try {
            if (!saveTo.exists()) return 0;
            return Integer.parseInt(new String(Files.readAllBytes(saveTo.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }
}
