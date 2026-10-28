package xyz.triosoft.serverdoctor.storage;

import xyz.triosoft.serverdoctor.model.ScanResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** Small bounded local index. All I/O writes must run off the server's primary thread. */
public final class HistoryStore {
    private static final Pattern ID_PATTERN = Pattern.compile("[0-9]{8}-[0-9]{6}-[a-f0-9]{6}");
    private final Path root;
    private final Path indexPath;
    private final List<Entry> entries = new ArrayList<>();

    public HistoryStore(Path dataFolder) throws IOException {
        this.root = dataFolder.resolve("reports");
        this.indexPath = dataFolder.resolve("history.properties");
        Files.createDirectories(root);
        if (Files.isRegularFile(indexPath)) {
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) { props.load(r); }
            for (String id : props.stringPropertyNames()) {
                if (!validId(id)) continue;
                try {
                    String[] values = props.getProperty(id).split("\\|", -1);
                    if (values.length != 5) continue;
                    entries.add(new Entry(id, Long.parseLong(values[0]), Integer.parseInt(values[1]),
                            Integer.parseInt(values[2]), Double.parseDouble(values[3]),
                            Double.parseDouble(values[4])));
                } catch (NumberFormatException ignored) { /* malformed entries ignored */ }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::epoch).reversed());
    }

    public static boolean validId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public Path exportPath(String id, String extension) {
        if (!validId(id) || !java.util.Set.of("json", "csv").contains(extension))
            throw new IllegalArgumentException("Invalid export path");
        return root.resolve("ServerDoctor-" + id + "." + extension);
    }
    public synchronized void pruneGeneratedReports() throws IOException {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Entry entry : entries) allowed.add(entry.id());
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("ServerDoctor-")) continue;
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = name.substring(dot + 1);
                String id = name.substring("ServerDoctor-".length(), dot);
                if (!java.util.Set.of("html", "json", "csv").contains(ext)) continue;
                if (validId(id) && !allowed.contains(id) && Files.isRegularFile(file)) Files.deleteIfExists(file);
            }
        }
    }
    public Path reportPath(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid report id");
        return root.resolve("ServerDoctor-" + id + ".html");
    }

    public synchronized List<Entry> recent(int max) {
        return List.copyOf(entries.subList(0, Math.min(Math.max(0, max), entries.size())));
    }

    public synchronized Entry latest() { return entries.isEmpty() ? null : entries.getFirst(); }

    public synchronized boolean contains(String id) { return entries.stream().anyMatch(e -> e.id().equals(id)); }

    public synchronized void add(ScanResult result, int historyLimit) throws IOException {
        int issues = (int) result.findings().stream()
                .filter(f -> f.severity() != ScanResult.Severity.INFO).count();
        entries.removeIf(e -> e.id().equals(result.id()));
        entries.add(new Entry(result.id(), result.createdAt().toEpochMilli(), result.score(),
                issues, result.tpsMean(), result.msptMean()));
        entries.sort(Comparator.comparingLong(Entry::epoch).reversed());
        while (entries.size() > historyLimit) entries.removeLast();
        Properties props = new Properties();
        for (Entry entry : entries) {
            props.setProperty(entry.id(), entry.epoch() + "|" + entry.score() + "|" + entry.issues()
                    + "|" + entry.tps() + "|" + entry.mspt());
        }
        Path temp = indexPath.resolveSibling("history.properties.tmp");
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            props.store(w, "ServerDoctor scan history | indexes reports; does not delete report files");
        }
        try {
            Files.move(temp, indexPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Entry(String id, long epoch, int score, int issues, double tps, double mspt) {
        public Instant instant() { return Instant.ofEpochMilli(epoch); }
    }
}
