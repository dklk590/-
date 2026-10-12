package com.wordweave.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Durable DownloadManager jobs followed by local verification, bounded extraction and atomic install. */
public final class ModelDownloadManager {
    private static final Object LOCK = new Object();
    private static ExecutorService installer;
    private static final Map<String, InstallJob> INSTALLS = new HashMap<>();
    private static final long RESERVE_BYTES = 32L * 1024 * 1024;
    private static final String MANIFEST = "wordweave-model.json";
    private static final class InstallJob {
        final ModelDownloadManager owner;
        final String token;
        Future<?> future;
        InstallJob(ModelDownloadManager owner, String token) { this.owner = owner; this.token = token; }
    }
    private final Context context;
    private final DownloadManager downloads;
    private final SharedPreferences preferences;
    private final File home, models;
    private final BroadcastReceiver receiver;
    private volatile boolean closed;

    public interface Cancellation { boolean isCancelled(); }

    public ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        downloads = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
        preferences = this.context.getSharedPreferences("offline_model_downloads_v1", Context.MODE_PRIVATE);
        home = new File(this.context.getNoBackupFilesDir(), "offline_tts");
        models = new File(home, "models");
        if (!models.isDirectory() && !models.mkdirs()) throw new IllegalStateException("无法创建离线模型目录。");
        synchronized (LOCK) {
                receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) refreshAll();
                    }
                };
                IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                // Public completion intents are hints only: actual state is always queried from our saved DownloadManager IDs.
                if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else this.context.registerReceiver(receiver, filter);
        }
        executor().submit(this::cleanOrphans);
        refreshAll();
    }

    /** This directory is app-private, never backed up, and never influenced by input paths. */
    public File installedModelDirectory(String id) {
        ModelCatalog.get(id);
        return new File(models, id);
    }

    /** Reads only metadata and required path existence; it does not load the ONNX model into memory. */
    public boolean isReady(String id) {
        ModelCatalog.Entry entry = ModelCatalog.get(id);
        File directory = installedModelDirectory(id);
        try {
            JSONObject manifest = new JSONObject(readSmall(new File(directory, MANIFEST), 64 * 1024));
            return entry.sha256.equals(manifest.optString("sha256"))
                    && entry.archiveBytes == manifest.optLong("archiveBytes", -1)
                    && validateInstalledFiles(directory, entry);
        } catch (Exception ignored) { return false; }
    }

    /** Ready-only descriptor with explicit absolute paths for the speech backend. */
    public JSONObject descriptor(String id) throws Exception {
        ModelCatalog.Entry entry = ModelCatalog.get(id);
        if (!isReady(id)) throw new IllegalStateException("此离线语音尚未安装，请先在软件内下载。");
        File directory = installedModelDirectory(id);
        JSONObject result = entry.toJson().put("ready", true).put("directory", directory.getAbsolutePath());
        result.put("modelPath", new File(directory, entry.modelFile).getAbsolutePath());
        result.put("tokensPath", new File(directory, entry.tokensFile).getAbsolutePath());
        result.put("voicesPath", absoluteOrEmpty(directory, entry.voicesFile));
        result.put("dataDir", new File(directory, entry.dataDir).getAbsolutePath());
        result.put("usLexicon", absoluteOrEmpty(directory, entry.usLexicon));
        result.put("gbLexicon", absoluteOrEmpty(directory, entry.gbLexicon));
        return result;
    }

    public JSONObject catalog() throws Exception {
        JSONArray entries = new JSONArray();
        for (ModelCatalog.Entry entry : ModelCatalog.all()) entries.put(status(entry.id));
        File external = context.getExternalFilesDir("tts_downloads");
        return new JSONObject().put("models", entries).put("availableStorageBytes", home.getUsableSpace())
                .put("availableDownloadStorageBytes", external == null ? 0 : external.getUsableSpace())
                .put("catalogVersion", ModelCatalog.CATALOG_VERSION);
    }

    /** Polling is fast; a successful download queues verification, never extracts on the calling/UI thread. */
    public JSONObject status(String id) throws Exception {
        synchronized (LOCK) {
            if (closed) throw new IllegalStateException("模型管理已关闭。");
            ModelCatalog.Entry entry = ModelCatalog.get(id);
            JSONObject record = load(id);
            JSONObject result = entry.toJson().put("downloadedBytes", record.optLong("downloadedBytes", 0))
                    .put("totalBytes", entry.archiveBytes).put("status", record.optString("status", "not_downloaded"))
                    .put("message", record.optString("message", ""));
            if (isReady(id)) {
                if (!INSTALLS.containsKey(id) && record.optLong("downloadId", -1) >= 0) {
                    removeDownload(record.optLong("downloadId"));
                    deleteQuietly(archiveFile(entry));
                    save(id, new JSONObject().put("status", "ready").put("message", "已安装，可离线朗读。")
                            .put("downloadedBytes", entry.archiveBytes));
                }
                return result.put("status", "ready").put("ready", true)
                        .put("downloadedBytes", entry.archiveBytes).put("message", "已安装，可离线朗读。");
            }
            long downloadId = record.optLong("downloadId", -1);
            String phase = record.optString("status", "not_downloaded");
            if (INSTALLS.containsKey(id)) return result.put("ready", false);
            if (downloadId < 0 || "cancelled".equals(phase) || "error".equals(phase)) return result.put("ready", false);
            if (downloads == null) return failStatus(id, entry, record, "系统下载服务不可用。");
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (cursor == null || !cursor.moveToFirst()) return failStatus(id, entry, record, "下载任务已被系统移除，请重新下载。");
                int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (bytes < 0) bytes = 0;
                if (bytes > entry.archiveBytes || (total > 0 && total != entry.archiveBytes)) {
                    removeDownload(downloadId);
                    return failStatus(id, entry, record, "下载内容大小与固定模型目录不一致，已停止。");
                }
                record.put("downloadedBytes", bytes);
                result.put("downloadedBytes", bytes);
                if (state == DownloadManager.STATUS_SUCCESSFUL) {
                    queueInstall(entry, record);
                    return result.put("status", "verifying").put("message", "下载完成，正在核验文件完整性。")
                            .put("downloadedBytes", entry.archiveBytes).put("ready", false);
                }
                if (state == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    return failStatus(id, entry, record, downloadFailure(reason));
                }
                String message = "正在下载，可离开此页面，系统会继续下载。";
                if (state == DownloadManager.STATUS_PAUSED) message = "下载等待网络或系统重试，可稍后回来查看。";
                if (state == DownloadManager.STATUS_PENDING) message = "模型下载已加入系统队列。";
                return result.put("status", "downloading").put("message", message).put("ready", false);
            }
        }
    }

    public JSONObject start(String id) throws Exception {
        synchronized (LOCK) {
            ModelCatalog.Entry entry = ModelCatalog.get(id);
            JSONObject current = status(id);
            String phase = current.optString("status");
            if (isReady(id) || "downloading".equals(phase) || "verifying".equals(phase) || "installing".equals(phase)) return current;
            if (downloads == null) throw new IllegalStateException("系统下载服务不可用。");
            File external = context.getExternalFilesDir("tts_downloads");
            if (external == null || (!external.isDirectory() && !external.mkdirs())) throw new IOException("下载存储空间当前不可用。");
            if (external.getUsableSpace() < entry.archiveBytes + RESERVE_BYTES
                    || home.getUsableSpace() < entry.archiveBytes + entry.unpackedBytes + RESERVE_BYTES)
                throw new IOException("可用空间不足，请先清理存储；下载和解压均需要空间。");
            JSONObject old = load(id);
            removeDownload(old.optLong("downloadId", -1));
            File archive = archiveFile(entry);
            if (archive.exists() && !archive.delete()) throw new IOException("无法清理旧下载文件。");
            String token = UUID.randomUUID().toString();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(entry.downloadUrl));
            request.setTitle("词链：" + entry.name).setDescription("离线语音模型按需下载，完成后在词链内校验并安装。")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, "tts_downloads", archive.getName());
            long downloadId = downloads.enqueue(request);
            try {
                save(id, new JSONObject().put("downloadId", downloadId).put("token", token).put("status", "downloading")
                        .put("message", "模型下载已加入系统队列。").put("downloadedBytes", 0));
            } catch (Exception error) {
                removeDownload(downloadId);
                deleteQuietly(archive);
                throw error;
            }
            return status(id);
        }
    }

    public JSONObject cancel(String id) throws Exception {
        synchronized (LOCK) {
            ModelCatalog.get(id);
            if (isReady(id)) return status(id); // Cancellation never removes an installed model.
            cancelLocked(id, "cancelled", "下载或安装已取消；可重新下载。");
            return status(id);
        }
    }

    /** Caller should stop/release a playing model before deleting it. This call itself does not block on recursive deletion. */
    public JSONObject delete(String id) throws Exception {
        synchronized (LOCK) {
            ModelCatalog.get(id);
            cancelLocked(id, "not_downloaded", "模型已删除，可按需重新下载。");
            File installed = installedModelDirectory(id);
            if (installed.exists()) {
                File trash = new File(home, ".delete-" + id + "-" + UUID.randomUUID());
                if (!installed.renameTo(trash)) throw new IOException("模型当前无法删除，请停止朗读后重试。");
                executor().submit(() -> deleteQuietly(trash));
            }
            return status(id);
        }
    }

    public void refreshAll() {
        if (closed) return;
        for (ModelCatalog.Entry entry : ModelCatalog.all()) {
            try { status(entry.id); } catch (Exception ignored) { /* The next explicit status request shows actionable errors. */ }
        }
    }

    public JSONObject installReady(String id) throws Exception { return status(id); }

    /** Activity teardown: downloads survive; verification/extraction resumes on the next manager instance. */
    public void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
            for (String id : new HashSet<>(INSTALLS.keySet())) {
                InstallJob job = INSTALLS.get(id);
                if (job == null || job.owner != this) continue;
                try {
                    JSONObject record = load(id);
                    if (!isReady(id) && record.optLong("downloadId", -1) >= 0)
                        save(id, record.put("token", UUID.randomUUID().toString()).put("status", "downloading")
                                .put("message", "完整下载已保留，下次打开软件时继续安装。"));
                } catch (Exception ignored) { }
                INSTALLS.remove(id);
                if (job.future != null) job.future.cancel(true);
            }
            if (INSTALLS.isEmpty() && installer != null) {
                installer.shutdownNow();
                installer = null;
            }
        }
    }

    private static ExecutorService executor() {
        synchronized (LOCK) {
            if (installer == null || installer.isShutdown()) installer = Executors.newSingleThreadExecutor();
            return installer;
        }
    }

    private void cancelLocked(String id, String status, String message) throws Exception {
        JSONObject old = load(id);
        save(id, new JSONObject().put("status", status).put("message", message).put("token", UUID.randomUUID().toString()));
        removeDownload(old.optLong("downloadId", -1));
        InstallJob task = INSTALLS.remove(id);
        if (task != null && task.future != null) task.future.cancel(true);
        deleteQuietly(archiveFile(ModelCatalog.get(id)));
    }

    private void queueInstall(ModelCatalog.Entry entry, JSONObject record) throws Exception {
        if (INSTALLS.containsKey(entry.id)) return;
        String token = record.optString("token", "");
        if (token.isEmpty()) throw new IOException("下载记录不完整，请重新下载。");
        record.put("status", "verifying").put("message", "正在核验 SHA-256。")
                .put("downloadedBytes", entry.archiveBytes);
        save(entry.id, record);
        InstallJob task = new InstallJob(this, token);
        INSTALLS.put(entry.id, task);
        task.future = executor().submit(() -> install(entry, task));
    }

    private void install(ModelCatalog.Entry entry, InstallJob job) {
        String token = job.token;
        File staging = new File(home, ".install-" + entry.id + "-" + token);
        Cancellation cancellation = () -> closed || Thread.currentThread().isInterrupted() || !isCurrent(entry.id, token);
        try {
            File archive = archiveFile(entry);
            verifyArchive(archive, entry.archiveBytes, entry.sha256, cancellation);
            checkCancelled(cancellation);
            if (home.getUsableSpace() < entry.unpackedBytes + RESERVE_BYTES) throw new IOException("安装空间不足，请清理存储后重新下载。");
            deleteQuietly(staging);
            if (!staging.mkdirs()) throw new IOException("无法创建模型安装临时目录。");
            updatePhase(entry.id, token, "installing", "文件校验通过，正在安全安装模型。");
            long total;
            try (InputStream input = new BufferedInputStream(new FileInputStream(archive));
                 InputStream bz = new BZip2CompressorInputStream(input, true)) {
                total = unpackTar(bz, staging, entry.archiveRoot, entry.unpackedBytes, cancellation);
            }
            if (total != entry.unpackedBytes || !validateInstalledFiles(staging, entry)) throw new IOException("模型包内容不完整，未安装。");
            JSONObject manifest = entry.toJson().put("catalogVersion", ModelCatalog.CATALOG_VERSION)
                    .put("installedAt", System.currentTimeMillis()).put("unpackedBytesVerified", total);
            writeFile(new File(staging, MANIFEST), manifest.toString());
            writeFile(new File(staging, "WORDWEAVE_LICENSE_NOTICE.txt"), entry.license + "\n" + entry.licenseNotice
                    + "\n模型来源：" + entry.sourceUrl + "\n下载包 SHA-256：" + entry.sha256 + "\n"
                    + "原下载包中的 LICENSE / MODEL_CARD 等文件完整保留，未删除。\n");
            synchronized (LOCK) {
                checkCancelled(cancellation);
                atomicInstall(staging, installedModelDirectory(entry.id));
                JSONObject old = load(entry.id);
                save(entry.id, new JSONObject().put("status", "ready").put("message", "已安装，可离线朗读。")
                        .put("downloadedBytes", entry.archiveBytes));
                removeDownload(old.optLong("downloadId", -1));
                deleteQuietly(archive);
            }
        } catch (Exception failure) {
            synchronized (LOCK) {
                if (!closed && isCurrent(entry.id, token)) {
                    try {
                        JSONObject old = load(entry.id);
                        removeDownload(old.optLong("downloadId", -1));
                        save(entry.id, new JSONObject().put("status", "error").put("message", friendlyFailure(failure)));
                    } catch (Exception ignored) { }
                    deleteQuietly(archiveFile(entry));
                }
            }
        } finally {
            deleteQuietly(staging);
            synchronized (LOCK) { if (INSTALLS.get(entry.id) == job) INSTALLS.remove(entry.id); }
        }
    }

    /** Host-testable, streaming SHA check. An archive is not trusted just because the HTTP request succeeded. */
    static void verifyArchive(File archive, long expectedBytes, String expectedSha, Cancellation cancellation) throws Exception {
        if (!archive.isFile() || archive.length() != expectedBytes) throw new IOException("下载文件大小不符，请重新下载。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            int read;
            while ((read = input.read(buffer)) != -1) { checkCancelled(cancellation); digest.update(buffer, 0, read); }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!expectedSha.equals(actual.toString())) throw new IOException("下载文件完整性校验失败，请重新下载。");
    }

    /** Bounded and link-free tar extraction into a fresh staging directory. Never applies tar permissions/ownership. */
    static long unpackTar(InputStream input, File staging, String archiveRoot, long byteLimit,
                          Cancellation cancellation) throws IOException {
        if (!archiveRoot.matches("[A-Za-z0-9_-]+") || byteLimit < 0) throw new IOException("Invalid extraction policy");
        if (!staging.isDirectory() || staging.list() == null || staging.list().length != 0) throw new IOException("Staging directory must be empty");
        File base = staging.getCanonicalFile();
        String prefix = base.getPath() + File.separator;
        Set<String> seen = new HashSet<>();
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry item;
            while ((item = tar.getNextTarEntry()) != null) {
                checkCancelled(cancellation);
                if (++count > 10000) throw new IOException("Archive has too many entries");
                byte type = item.getLinkFlag();
                boolean supported = (type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM) && item.isFile()
                        || type == TarConstants.LF_DIR && item.isDirectory();
                if (!supported || item.isSymbolicLink() || item.isLink()
                        || item.isSparse() || !tar.canReadEntryData(item)) throw new IOException("Archive contains an unsupported link or file type");
                String name = item.getName();
                if (name.length() > 1024 || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                        || name.startsWith("/")) throw new IOException("Invalid archive path");
                while (name.startsWith("./")) name = name.substring(2);
                while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (name.equals(archiveRoot)) { if (!item.isDirectory()) throw new IOException("Invalid archive root"); continue; }
                if (!name.startsWith(archiveRoot + "/")) throw new IOException("Archive escapes the declared root");
                String relative = name.substring(archiveRoot.length() + 1);
                for (String segment : relative.split("/", -1)) {
                    if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IOException("Archive path traversal rejected");
                }
                if (!seen.add(relative)) throw new IOException("Duplicate archive path rejected");
                File output = new File(base, relative).getCanonicalFile();
                if (!output.getPath().startsWith(prefix)) throw new IOException("Archive path traversal rejected");
                if (item.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create archive directory");
                    continue;
                }
                long size = item.getSize();
                if (size < 0 || size > byteLimit - total) throw new IOException("Archive exceeds its allowed expanded size");
                total += size;
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create archive parent");
                long copied = 0;
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    int read;
                    while ((read = tar.read(buffer)) != -1) {
                        checkCancelled(cancellation);
                        copied += read;
                        if (copied > size) throw new IOException("Archive entry exceeds declared size");
                        stream.write(buffer, 0, read);
                    }
                    stream.getFD().sync();
                }
                if (copied != size) throw new IOException("Truncated archive entry");
            }
        }
        checkCancelled(cancellation);
        return total;
    }

    static void atomicInstall(File staging, File installed) throws IOException {
        File parent = installed.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create install directory");
        File backup = new File(parent, ".previous-" + installed.getName() + "-" + UUID.randomUUID());
        boolean previous = installed.exists();
        if (previous && !installed.renameTo(backup)) throw new IOException("Cannot preserve existing model");
        if (!staging.renameTo(installed)) {
            if (previous && !backup.renameTo(installed)) throw new IOException("Install failed and previous model could not be restored");
            throw new IOException("Cannot atomically install model");
        }
        deleteQuietly(backup);
    }

    static boolean validateInstalledFiles(File directory, ModelCatalog.Entry entry) throws IOException {
        String base = directory.getCanonicalPath() + File.separator;
        for (String path : entry.requiredFiles()) {
            File file = new File(directory, path);
            if (!file.getCanonicalPath().startsWith(base) || !file.isFile() || file.length() <= 0
                    || Files.isSymbolicLink(file.toPath())) return false;
        }
        File data = new File(directory, entry.dataDir);
        if (!data.isDirectory() || Files.isSymbolicLink(data.toPath())) return false;
        for (String name : new String[]{"phontab", "phonindex", "phondata", "en_dict"}) {
            File phonemeData = new File(data, name);
            if (!phonemeData.getCanonicalPath().startsWith(base) || !phonemeData.isFile()
                    || phonemeData.length() <= 0 || Files.isSymbolicLink(phonemeData.toPath())) return false;
        }
        return true;
    }

    private void cleanOrphans() {
        File[] previous = models.listFiles();
        if (previous != null) for (File file : previous) if (file.getName().startsWith(".previous-")) {
            boolean restoreNeeded = false;
            for (ModelCatalog.Entry entry : ModelCatalog.all()) {
                if (file.getName().startsWith(".previous-" + entry.id + "-") && !installedModelDirectory(entry.id).exists()) {
                    restoreNeeded = true;
                    file.renameTo(installedModelDirectory(entry.id));
                    break;
                }
            }
            if (!restoreNeeded) deleteQuietly(file);
        }
        File[] temporary = home.listFiles();
        if (temporary == null) return;
        for (File file : temporary) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            String name = file.getName();
            if (name.startsWith(".delete-")) deleteQuietly(file);
            if (name.startsWith(".install-")) {
                boolean active = false;
                synchronized (LOCK) {
                    for (Map.Entry<String, InstallJob> job : INSTALLS.entrySet())
                        if (name.equals(".install-" + job.getKey() + "-" + job.getValue().token)) active = true;
                }
                if (!active) deleteQuietly(file);
            }
        }
    }

    private JSONObject failStatus(String id, ModelCatalog.Entry entry, JSONObject record, String message) throws Exception {
        save(id, record.put("status", "error").put("message", message));
        return entry.toJson().put("status", "error").put("message", message).put("ready", false)
                .put("downloadedBytes", record.optLong("downloadedBytes", 0)).put("totalBytes", entry.archiveBytes);
    }

    private JSONObject load(String id) throws Exception { return new JSONObject(preferences.getString(id, "{}")); }
    private void save(String id, JSONObject state) throws IOException {
        if (!preferences.edit().putString(id, state.toString()).commit()) throw new IOException("模型下载状态保存失败。");
    }
    private boolean isCurrent(String id, String token) {
        synchronized (LOCK) {
            try { return token.equals(load(id).optString("token", "")); } catch (Exception ignored) { return false; }
        }
    }
    private void updatePhase(String id, String token, String status, String message) throws Exception {
        synchronized (LOCK) {
            if (!isCurrent(id, token)) throw new IOException("Model task cancelled");
            save(id, load(id).put("status", status).put("message", message));
        }
    }
    private File archiveFile(ModelCatalog.Entry entry) {
        File directory = context.getExternalFilesDir("tts_downloads");
        if (directory == null) throw new IllegalStateException("下载存储空间当前不可用。");
        return new File(directory, entry.id + "-" + entry.sha256.substring(0, 12) + ".tar.bz2");
    }
    private void removeDownload(long id) { if (downloads != null && id >= 0) try { downloads.remove(id); } catch (Exception ignored) { } }
    private static String absoluteOrEmpty(File base, String relative) { return relative.isEmpty() ? "" : new File(base, relative).getAbsolutePath(); }
    private static void checkCancelled(Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("Model task cancelled");
    }
    private static String readSmall(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit) throw new IOException("Invalid model manifest");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > limit) throw new IOException("Invalid model manifest");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void writeFile(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
    }
    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteQuietly(child);
        }
        file.delete();
    }
    private static String downloadFailure(int reason) {
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "系统下载空间不足，请清理后重试。";
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "下载存储设备当前不可用。";
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) return "下载目标文件已存在，请取消后重新下载。";
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR || reason == DownloadManager.ERROR_CANNOT_RESUME)
            return "网络下载中断且无法续传，请重新下载。";
        if (reason == 404) return "官方模型包暂不可用，请稍后重试。";
        return "系统未能完成模型下载（" + reason + "），请检查网络后重试。";
    }
    private static String friendlyFailure(Exception error) {
        String message = error.getMessage();
        if (message != null && (message.contains("校验") || message.contains("空间") || message.contains("完整") || message.contains("大小"))) return message;
        return "模型安装失败，未启用不完整模型；请重新下载。";
    }
}
