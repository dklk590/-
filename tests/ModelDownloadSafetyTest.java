package com.wordweave.app;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Actual pinned archive verification plus crafted hostile-tar checks. No external network in this test. */
public final class ModelDownloadSafetyTest {
    private static int checks;
    private static final ModelDownloadManager.Cancellation NEVER = () -> false;

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("wordweave-model-test-");
        try {
            Set<String> ids = new HashSet<>();
            for (ModelCatalog.Entry entry : ModelCatalog.all()) {
                check(ids.add(entry.id) && entry.downloadUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"), "fixed official catalog");
                check(entry.sha256.matches("[0-9a-f]{64}") && entry.archiveBytes > 0 && entry.unpackedBytes > 0, "pinned integrity metadata");
                Set<Integer> speakers = new HashSet<>();
                for (ModelCatalog.Speaker speaker : entry.speakers) check(speakers.add(speaker.id) && entry.hasSpeaker(speaker.id), "speaker ids");
                check(!entry.hasSpeaker(999), "unknown speaker rejected");
            }
            fails(() -> ModelCatalog.get("../../escape"), "catalog path injection rejected");
            fails(() -> ModelCatalog.get("https://example.com/other-model"), "user URL rejected");
            check(ModelCatalog.get(ModelCatalog.DEFAULT_MODEL_ID).speakers.size() == 8, "eight kitten voices");
            check(ModelCatalog.get("kokoro-int8-multi-lang-v1_0").speakers.size() == 28, "only confirmed English kokoro voices listed");
            File small = temporary.resolve("small.bin").toFile();
            Files.write(small.toPath(), "integrity test".getBytes(StandardCharsets.UTF_8));
            String sha = hash(Files.readAllBytes(small.toPath()));
            ModelDownloadManager.verifyArchive(small, small.length(), sha, NEVER);
            checks++;
            fails(() -> ModelDownloadManager.verifyArchive(small, small.length() + 1, sha, NEVER), "wrong size rejected");
            fails(() -> ModelDownloadManager.verifyArchive(small, small.length(), "0".repeat(64), NEVER), "corrupted digest rejected");
            fails(() -> ModelDownloadManager.verifyArchive(small, small.length(), sha, () -> true), "hash cancellation");

            byte[] normal = archive(new Item("root/data/file.txt", "hello", TarConstants.LF_NORMAL, null));
            File stage = stage(temporary, "valid");
            check(ModelDownloadManager.unpackTar(new ByteArrayInputStream(normal), stage, "root", 5, NEVER) == 5, "bounded tar extraction");
            check(Files.readString(stage.toPath().resolve("data/file.txt")).equals("hello"), "nested file content");
            failsTar(temporary, "traversal", new Item("root/../escape", "bad", TarConstants.LF_NORMAL, null));
            failsTar(temporary, "absolute", new Item("/outside", "bad", TarConstants.LF_NORMAL, null));
            failsTar(temporary, "windows", new Item("root/C:\\outside", "bad", TarConstants.LF_NORMAL, null));
            failsTar(temporary, "backslash", new Item("root/dir\\outside", "bad", TarConstants.LF_NORMAL, null));
            failsTar(temporary, "wrongroot", new Item("different/file", "bad", TarConstants.LF_NORMAL, null));
            failsTar(temporary, "symlink", new Item("root/link", "", TarConstants.LF_SYMLINK, "/outside"));
            failsTar(temporary, "hardlink", new Item("root/link", "", TarConstants.LF_LINK, "root/file"));
            failsTar(temporary, "fifo", new Item("root/pipe", "", TarConstants.LF_FIFO, null));
            byte[] duplicate = archive(new Item("root/file", "first", TarConstants.LF_NORMAL, null), new Item("root/file", "second", TarConstants.LF_NORMAL, null));
            fails(() -> ModelDownloadManager.unpackTar(new ByteArrayInputStream(duplicate), stage(temporary, "duplicate"), "root", 100, NEVER), "duplicate overwrite rejected");
            fails(() -> ModelDownloadManager.unpackTar(new ByteArrayInputStream(normal), stage(temporary, "bomb"), "root", 4, NEVER), "expanded size cap enforced");
            fails(() -> ModelDownloadManager.unpackTar(new ByteArrayInputStream(normal), stage(temporary, "cancel"), "root", 10, () -> true), "extraction cancellation");
            fails(() -> ModelDownloadManager.unpackTar(new ByteArrayInputStream(normal), stage, "root", 10, NEVER), "preexisting staging rejected");
            check(!temporary.resolve("escape").toFile().exists(), "no traversal output created");

            File installed = temporary.resolve("installed").toFile();
            installed.mkdirs(); Files.writeString(installed.toPath().resolve("version"), "old");
            File replacement = stage(temporary, "replacement"); Files.writeString(replacement.toPath().resolve("version"), "new");
            ModelDownloadManager.atomicInstall(replacement, installed);
            check(Files.readString(installed.toPath().resolve("version")).equals("new") && !replacement.exists(), "atomic complete install replaces old");
            fails(() -> ModelDownloadManager.atomicInstall(temporary.resolve("missing").toFile(), installed), "atomic failed install");
            check(Files.readString(installed.toPath().resolve("version")).equals("new"), "previous model restored after failed install");

            if (args.length == 1) {
                for (ModelCatalog.Entry entry : ModelCatalog.all()) {
                    File archive = new File(args[0], entry.id + ".tar.bz2");
                    ModelDownloadManager.verifyArchive(archive, entry.archiveBytes, entry.sha256, NEVER);
                    File target = stage(temporary, entry.id);
                    long bytes;
                    try (FileInputStream file = new FileInputStream(archive);
                         BZip2CompressorInputStream bz = new BZip2CompressorInputStream(file, true)) {
                        bytes = ModelDownloadManager.unpackTar(bz, target, entry.archiveRoot, entry.unpackedBytes, NEVER);
                    }
                    check(bytes == entry.unpackedBytes && ModelDownloadManager.validateInstalledFiles(target, entry), "real archive hash, content and required speech files");
                    System.out.println("Verified and safely extracted: " + entry.id + "; bytes=" + bytes);
                }
            }
            System.out.println("Model download safety checks passed: " + checks);
        } finally { delete(temporary.toFile()); }
    }

    private static File stage(Path base, String name) throws Exception { return Files.createDirectory(base.resolve(name)).toFile(); }
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    private static void fails(Action action, String name) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError(name);
    }
    private static void failsTar(Path temporary, String label, Item item) throws Exception {
        byte[] data = archive(item);
        fails(() -> ModelDownloadManager.unpackTar(new ByteArrayInputStream(data), stage(temporary, label), "root", 100, NEVER), label + " tar rejected");
    }
    private static String hash(byte[] input) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(input)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static final class Item {
        final String name, text, link; final byte type;
        Item(String name, String text, byte type, String link) { this.name = name; this.text = text; this.type = type; this.link = link; }
    }
    private static byte[] archive(Item... items) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            for (Item item : items) {
                TarArchiveEntry entry = new TarArchiveEntry(item.name, item.type, true);
                if (item.link != null) entry.setLinkName(item.link);
                byte[] text = item.text.getBytes(StandardCharsets.UTF_8);
                entry.setSize(item.type == TarConstants.LF_NORMAL ? text.length : 0);
                tar.putArchiveEntry(entry);
                if (item.type == TarConstants.LF_NORMAL) tar.write(text);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return bytes.toByteArray();
    }
    private static void delete(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}
