package org.knime.targetinstaller.utils;
// java
import java.util.Objects;
import java.util.regex.Pattern;

public class ProvisioningJobUpdateLineParser {

    public static class BundleId {
        private final String bundleId;

        public BundleId(String bundleId) {
            this.bundleId = bundleId;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;

            if (o == null || getClass() != o.getClass()) return false;

            BundleId bundleId1 = (BundleId) o;

            return Objects.equals(bundleId, bundleId1.bundleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bundleId);
        }

        @Override
        public String toString() {
            return this.bundleId;
        }
    }

    public enum Action {
        FETCHING,
        OTHER
    }

    public static record ProvisioningJobUpdate(Action action, BundleId bundleId, String source, String downloaded, String total, String speed) {}

    private static final Pattern MAIN = Pattern.compile(
            "^([A-Za-z]+)\\s+(.+?)\\s+from\\s+(\\S+)(?:\\s*\\(([^)]*)\\))?.*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PAREN_FULL = Pattern.compile("([^\\s]+)\\s+of\\s+([^\\s]+)(?:\\s+at\\s+([^\\s]+))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a single "Fetching ..." line and return a ProvisioningJobUpdate record.
     * Example input:
     *   "Fetching com.microsoft.graph.microsoft-graph_5.49.0.v20230328-knime.jar from https://.../plugins/ (1,022.56kB of 10.23MB at 1,022)"
     */
    public static ProvisioningJobUpdate parseUpdateLine(String line) {
        if (line == null) return new ProvisioningJobUpdate(Action.OTHER, null, null, null, null, null);

        var m = MAIN.matcher(line);
        if (!m.matches()) {
            return new ProvisioningJobUpdate(Action.OTHER, null, null, null, null, null);
        }

        var actionToken = m.group(1);    // e.g. "Fetching"
        var fileToken = m.group(2);      // e.g. com.microsoft.graph.microsoft-graph_5.49.0.v20230328-knime.jar
        var source = m.group(3);         // e.g. https://.../plugins/
        var paren = m.group(4);          // e.g. "1,022.56kB of 10.23MB at 1,022" (may be null)

        Action action = Action.OTHER;
        if (actionToken != null && actionToken.equalsIgnoreCase("Fetching")) {
            action = Action.FETCHING;
        }

        // derive bundleId: strip ".jar" and drop trailing "_<version...>" if it looks like a version
        String bundleId = null;
        if (fileToken != null) {
            var name = fileToken;
            if (name.endsWith(".jar")) {
                name = name.substring(0, name.length() - 4);
            }
//            var lastUnderscore = name.lastIndexOf('_');
//            if (lastUnderscore > 0) {
//                var after = name.substring(lastUnderscore + 1);
//                // treat as version if it starts with a digit (common pattern)
//                if (!after.isEmpty() && Character.isDigit(after.charAt(0))) {
//                    bundleId = name.substring(0, lastUnderscore);
//                } else {
//                    bundleId = name;
//                }
//            } else {
//                bundleId = name;
//            }
            bundleId = name;
        }

        String downloaded = null, total = null, speed = null;
        if (paren != null && !paren.isBlank()) {
            var p = PAREN_FULL.matcher(paren.trim());
            if (p.find()) {
                downloaded = p.group(1);
                total = p.group(2);
                speed = p.group(3); // may be null
            } else {
                // fallback: maybe only one token present (treat as total)
                var single = paren.trim().split("\\s+")[0];
                if (!single.isEmpty()) {
                    total = single;
                }
            }
        }

        return new ProvisioningJobUpdate(action, new BundleId(bundleId), source, downloaded, total, speed);
    }

    // quick demo
    public static void main(String[] args) {
        var line = "Fetching com.microsoft.graph.microsoft-graph_5.49.0.v20230328-knime.jar from https://update.knime.com/analytics-platform/nightly/plugins/ (1,022.56kB of 10.23MB at 1,022kB)";
        var info = parseUpdateLine(line);
        System.out.println("action   : " + info.action());
        System.out.println("bundleId : " + info.bundleId());
        System.out.println("source   : " + info.source());
        System.out.println("downloaded: " + info.downloaded());
        System.out.println("total    : " + info.total());
        System.out.println("speed    : " + info.speed());
    }
}
