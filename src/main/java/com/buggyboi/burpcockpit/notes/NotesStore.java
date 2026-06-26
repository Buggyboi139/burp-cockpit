package com.buggyboi.burpcockpit.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class NotesStore {
    private static final Pattern BAD_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private Path root;

    public NotesStore(Path root) {
        this.root = root;
        ensureRoot();
    }

    public synchronized Path root() {
        return root;
    }

    public synchronized void root(Path root) {
        this.root = root;
        ensureRoot();
    }

    public synchronized List<String> listNoteNames() {
        ensureRoot();
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(path -> out.add(stripExtension(path.getFileName().toString())));
        } catch (IOException ignored) {
            // UI will show empty list. The crime is survivable.
        }
        return out;
    }

    public synchronized boolean exists(String requestedName) {
        ensureRoot();
        return Files.exists(pathFor(requestedName));
    }

    public synchronized String read(String requestedName) {
        ensureRoot();
        Path path = pathFor(requestedName);
        if (!Files.exists(path)) {
            return defaultContent(sanitizeName(requestedName));
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "# " + sanitizeName(requestedName) + "\n\nFailed to read note: " + ex.getMessage() + "\n";
        }
    }

    public synchronized void write(String requestedName, String content) throws IOException {
        ensureRoot();
        Files.writeString(pathFor(requestedName), Objects.toString(content, ""), StandardCharsets.UTF_8);
    }

    public synchronized String rename(String oldRequestedName, String newRequestedName) throws IOException {
        ensureRoot();
        String oldName = sanitizeName(oldRequestedName);
        String newName = sanitizeName(newRequestedName);
        if (oldName.equals(newName)) {
            return newName;
        }
        Path oldPath = pathFor(oldName);
        Path newPath = pathFor(newName);
        if (!Files.exists(oldPath)) {
            throw new IOException("Cannot rename missing note: " + oldName);
        }
        if (Files.exists(newPath)) {
            throw new IOException("Note already exists: " + newName);
        }
        try {
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(oldPath, newPath);
        }
        return newName;
    }

    public synchronized String ensureNote(String requestedName) {
        ensureRoot();
        String name = sanitizeName(requestedName);
        Path path = pathFor(name);
        if (!Files.exists(path)) {
            try {
                Files.writeString(path, defaultContent(name), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // The caller can still show unsaved content.
            }
        }
        return name;
    }

    public static String sanitizeName(String name) {
        String cleaned = Objects.toString(name, "DEFAULT").trim();
        if (cleaned.isBlank()) {
            cleaned = "DEFAULT";
        }
        cleaned = cleaned.replace('/', '.').replace('\\', '.');
        cleaned = BAD_NAME_CHARS.matcher(cleaned).replaceAll("_");
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", ".");
        }
        return cleaned;
    }

    private synchronized Path pathFor(String requestedName) {
        return root.resolve(sanitizeName(requestedName) + ".md").normalize();
    }

    private synchronized void ensureRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {
            // UI will surface write failures when they happen.
        }
    }

    private static String stripExtension(String filename) {
        return filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private static String defaultContent(String name) {
        return "# " + name + "\n\nCreated: " + Instant.now() + "\n\n## Notes\n\n";
    }
}
