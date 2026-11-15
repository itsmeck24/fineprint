package io.github.itsmeck24.fineprint.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeVisitor;

public final class PostingText {

    private PostingText() {
    }

    // one line per block element, whitespace collapsed, empty lines dropped
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Jsoup.parseBodyFragment(html).body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    sb.append(t.getWholeText());
                } else if (node instanceof Element e && (e.isBlock() || e.nameIs("br"))) {
                    sb.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && e.isBlock()) {
                    sb.append('\n');
                }
            }
        });
        List<String> lines = new ArrayList<>();
        for (String line : sb.toString().split("\n")) {
            String clean = line.replace(' ', ' ').replaceAll("\\s+", " ").strip();
            if (!clean.isEmpty()) {
                lines.add(clean);
            }
        }
        return String.join("\n", lines);
    }

    // greenhouse sends the description html entity-encoded, so decode once before parsing tags
    public static String greenhouseContentToText(String content) {
        if (content == null) {
            return "";
        }
        return htmlToText(Parser.unescapeEntities(content, false));
    }

    public static String hash(String title, String location, String bodyText, String formJson) {
        String joined = String.join("\u0000",
                nz(title), nz(location), nz(bodyText), nz(formJson));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
