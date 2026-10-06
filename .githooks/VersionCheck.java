import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Used by .githooks/pre-push and bump-version.
 *
 *   java VersionCheck.java check <localSha> <remoteSha> <branch> <remoteName>
 *   java VersionCheck.java bump
 *
 * Only the root pom.xml's own <version> is considered. Fails closed: any state
 * it cannot evaluate blocks the push with an explanation.
 */
public class VersionCheck {

    static final String BUMP_CMD = ".githooks\\bump-version.cmd";
    static File root;

    // ---------------------------------------------------------------- main

    public static void main(String[] a) {
        try {
            root = new File(run(null, false, "rev-parse", "--show-toplevel").out().trim());
            if (a.length == 5 && a[0].equals("check")) System.exit(check(a[1], a[2], a[3], a[4]));
            if (a.length == 1 && a[0].equals("bump")) System.exit(bump());
            System.err.println("usage: VersionCheck.java check <localSha> <remoteSha> <branch> <remote> | bump");
            System.exit(2);
        } catch (Block b) {
            System.err.println(b.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("version check failed unexpectedly (push blocked): " + e);
            System.exit(1);
        }
    }

    // --------------------------------------------------------------- check

    static int check(String localSha, String remoteSha, String branch, String remote) throws Exception {
        if (localSha.equals(remoteSha)) return 0;
        String head = "pre-push: push to '" + branch + "' blocked.\n  ";

        Version local = pomVersion(localSha, "the commit you are pushing", head);
        Version base;
        String baseLabel;

        if (remoteSha.matches("0+")) {
            Result d = run(null, false, "describe", "--tags", "--abbrev=0", localSha);
            if (d.code() != 0) {
                throw new Block(head + "'" + branch + "' does not exist on the remote and no tag is reachable from\n"
                        + "  the pushed commit, so there is no baseline version. Tag the last release, or push once with --no-verify.");
            }
            String tag = d.out().trim();
            base = Version.parse(tag);
            if (base == null) throw new Block(head + "nearest tag '" + tag + "' is not MAJOR.MINOR.PATCH[-prerelease].");
            baseLabel = "nearest tag " + tag;
        } else {
            String shortSha = remoteSha.substring(0, 7);
            if (run(null, false, "cat-file", "-e", remoteSha + "^{commit}").code() != 0) {
                throw new Block(head + "remote tip " + shortSha + " of '" + branch + "' is not in your local repository.\n"
                        + "  Run: git fetch   then push again.");
            }
            base = pomVersion(remoteSha, remote + "/" + branch + " (" + shortSha + ")", head);
            baseLabel = remote + "/" + branch + " (" + shortSha + ")";
        }

        if (local.compareTo(base) <= 0) {
            throw new Block(head + "pom.xml version is " + local + " but must be greater than " + base + " on " + baseLabel + ".\n"
                    + "  Fix: " + BUMP_CMD + "   then push again.");
        }

        if (run(null, false, "rev-parse", "-q", "--verify", "refs/tags/" + local).code() != 0) {
            System.err.println("pre-push: warning: version " + local + " has no local tag '" + local + "'. "
                    + "(bump-version creates it; push it with: git push " + remote + " " + local + ")");
        }
        return 0;
    }

    static Version pomVersion(String sha, String what, String head) throws Exception {
        Result r = run(null, false, "show", sha + ":pom.xml");
        if (r.code() != 0) throw new Block(head + "no pom.xml at the repository root in " + what + ".");
        String v = readProjectVersion(r.bytes(), what, head);
        Version parsed = Version.parse(v);
        if (parsed == null) {
            throw new Block(head + "version '" + v + "' in " + what + " is not MAJOR.MINOR.PATCH[-prerelease].");
        }
        return parsed;
    }

    static String readProjectVersion(byte[] pom, String what, String head) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        Document d;
        try {
            d = f.newDocumentBuilder().parse(new ByteArrayInputStream(pom));
        } catch (Exception e) {
            throw new Block(head + "pom.xml in " + what + " is not parseable XML: " + e.getMessage());
        }
        String v = null;
        for (Node n = d.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("version")) v = e.getTextContent().trim();
        }
        if (v == null) {
            throw new Block(head + "the root pom.xml in " + what + " declares no <version> of its own\n"
                    + "  (inherited from a parent?). Not supported by this hook.");
        }
        if (v.contains("${")) {
            throw new Block(head + "version '" + v + "' in " + what + " is a property placeholder. Not supported by this hook.");
        }
        return v;
    }

    // ---------------------------------------------------------------- bump

    static int bump() throws Exception {
        if (!run(null, false, "status", "--porcelain", "--", "pom.xml").out().isBlank()) {
            throw new Block("bump-version: pom.xml has uncommitted changes. Commit or stash them first.");
        }
        Version cur = pomVersion("HEAD", "HEAD", "bump-version: ");
        List<String> labels = new ArrayList<>();
        List<Version> cands = new ArrayList<>();
        if (!cur.pre().isEmpty()) {
            labels.add("release");
            cands.add(new Version(cur.major(), cur.minor(), cur.patch(), List.of()));
            List<String> next = new ArrayList<>(cur.pre());
            String last = next.get(next.size() - 1);
            if (last.matches("\\d+")) next.set(next.size() - 1, new BigInteger(last).add(BigInteger.ONE).toString());
            else next.add("1");
            labels.add("next prerelease");
            cands.add(new Version(cur.major(), cur.minor(), cur.patch(), next));
        }
        labels.add("patch");
        cands.add(new Version(cur.major(), cur.minor(), cur.patch() + 1, List.of()));
        labels.add("minor");
        cands.add(new Version(cur.major(), cur.minor() + 1, 0, List.of()));
        labels.add("major");
        cands.add(new Version(cur.major() + 1, 0, 0, List.of()));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String branch = run(null, false, "rev-parse", "--abbrev-ref", "HEAD").out().trim();
        System.out.println("Branch: " + branch + "    Current version (HEAD): " + cur);
        for (int i = 0; i < cands.size(); i++) {
            System.out.printf("  %d) %-16s -> %s%n", i + 1, labels.get(i), cands.get(i));
        }

        Version chosen = null;
        while (chosen == null) {
            System.out.print("Choose a number, or type a version (empty = abort): ");
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.isBlank()) {
                System.out.println("Aborted.");
                return 1;
            }
            line = line.trim();
            Version v = null;
            if (line.matches("\\d{1,2}") && Integer.parseInt(line) >= 1 && Integer.parseInt(line) <= cands.size()) {
                v = cands.get(Integer.parseInt(line) - 1);
            } else {
                v = Version.parse(line);
                if (v == null) System.out.println("  Not a valid MAJOR.MINOR.PATCH[-prerelease] version.");
            }
            if (v != null && v.compareTo(cur) <= 0) {
                System.out.println("  " + v + " is not greater than " + cur + ".");
                v = null;
            }
            chosen = v;
        }

        String tag = chosen.toString();
        if (run(null, false, "rev-parse", "-q", "--verify", "refs/tags/" + tag).code() == 0) {
            throw new Block("bump-version: tag '" + tag + "' already exists. Nothing changed.");
        }
        System.out.print("Bump to " + chosen + ", commit and tag '" + tag + "'? [Y/n] ");
        System.out.flush();
        String ans = in.readLine();
        if (ans != null && !ans.isBlank() && !ans.trim().equalsIgnoreCase("y")) {
            System.out.println("Aborted.");
            return 1;
        }

        Path pom = root.toPath().resolve("pom.xml");
        // ISO-8859-1 round-trips every byte, so encoding and line endings are preserved exactly.
        String xml = Files.readString(pom, StandardCharsets.ISO_8859_1);
        Files.writeString(pom, replaceProjectVersion(xml, cur.toString(), chosen.toString()), StandardCharsets.ISO_8859_1);

        Result c = run(null, true, "commit", "--only", "-m", "build: release " + chosen, "--", "pom.xml");
        if (c.code() != 0) throw new Block("bump-version: git commit failed:\n" + c.out());
        Result t = run(null, true, "tag", "-a", tag, "-m", "Release " + tag);
        if (t.code() != 0) throw new Block("bump-version: commit done, but tagging failed:\n" + t.out());

        System.out.println("Done: committed " + chosen + " and created tag " + tag + ".");
        System.out.println("Next: git push   (tag is pushed too if push.followTags is set; otherwise: git push <remote> " + tag + ")");
        return 0;
    }

    /** Replaces the text of the project's own <version> (depth 1), never the <parent> one. */
    static String replaceProjectVersion(String xml, String oldV, String newV) throws Block {
        StringBuilder masked = new StringBuilder(xml);
        Matcher skip = Pattern.compile("<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<!DOCTYPE[^>]*>", Pattern.DOTALL).matcher(xml);
        while (skip.find()) {
            for (int i = skip.start(); i < skip.end(); i++) masked.setCharAt(i, ' ');
        }
        Matcher tag = Pattern.compile("<(/?)([A-Za-z_][\\w.:-]*)[^>]*?(/?)>").matcher(masked);
        int depth = 0;
        while (tag.find()) {
            if (!tag.group(1).isEmpty()) { depth--; continue; }
            boolean selfClosing = !tag.group(3).isEmpty();
            if (!selfClosing) depth++;
            if (!selfClosing && depth == 2 && tag.group(2).equals("version")) {
                int start = tag.end();
                int end = masked.indexOf("</version>", start);
                if (end > 0 && xml.substring(start, end).trim().equals(oldV)) {
                    return xml.substring(0, start) + newV + xml.substring(end);
                }
                break;
            }
        }
        throw new Block("bump-version: could not locate <version>" + oldV + "</version> in pom.xml to edit. Nothing changed.");
    }

    // ------------------------------------------------------------- helpers

    static class Block extends Exception {
        Block(String m) { super(m); }
    }

    record Result(int code, byte[] bytes) {
        String out() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    static Result run(File dir, boolean mergeErr, String... gitArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(gitArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir);
        else if (root != null) pb.directory(root);
        if (mergeErr) pb.redirectErrorStream(true);
        else pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        return new Result(p.waitFor(), out);
    }

    /** MAJOR.MINOR.PATCH[-prerelease], ordered per semver 2.0 (no build metadata). */
    record Version(int major, int minor, int patch, List<String> pre) implements Comparable<Version> {
        static final Pattern P = Pattern.compile(
                "(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:-([0-9A-Za-z]+(?:\\.[0-9A-Za-z]+)*))?");

        static Version parse(String s) {
            Matcher m = P.matcher(s);
            if (!m.matches()) return null;
            List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
            return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), pre);
        }

        @Override
        public int compareTo(Version o) {
            int c = Integer.compare(major, o.major);
            if (c != 0) return c;
            c = Integer.compare(minor, o.minor);
            if (c != 0) return c;
            c = Integer.compare(patch, o.patch);
            if (c != 0) return c;
            if (pre.isEmpty() && o.pre.isEmpty()) return 0;
            if (pre.isEmpty()) return 1;
            if (o.pre.isEmpty()) return -1;
            for (int i = 0; i < Math.min(pre.size(), o.pre.size()); i++) {
                String x = pre.get(i), y = o.pre.get(i);
                boolean xn = x.matches("\\d+"), yn = y.matches("\\d+");
                int r;
                if (xn && yn) r = new BigInteger(x).compareTo(new BigInteger(y));
                else if (xn) r = -1;
                else if (yn) r = 1;
                else r = x.compareTo(y);
                if (r != 0) return r;
            }
            return Integer.compare(pre.size(), o.pre.size());
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + String.join(".", pre));
        }
    }
}
