package app.morphe.extension.music.localaudio;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays a local audio file instead of streaming when a file named "... [VIDEOID].m4a|webm"
 * exists in the app's external files dir ("local" subdirectory).
 *
 * How it works:
 *  - Hook A (setCurrentVideoId) records the current video ID when a player response is parsed.
 *  - Hook B (redirectToLocal) is called with the stream Uri just before the request is made.
 *    If a byte-compatible local file exists, the Uri is swapped for a loopback HTTP URL served
 *    by the tiny server below.
 *
 * The file must be the RAW stream YouTube serves for the requested itag (e.g. yt-dlp -f 140 or
 * -f 251 with NO re-encoding), because the player already has that format's init/index ranges.
 */
@SuppressWarnings("unused")
public final class LocalAudioPatch {
    private static final String TAG = "LocalAudio";

    /** Subdirectory of getExternalFilesDir() (needs no storage permission). */
    private static final String SUBDIR = "local";

    /**
     * If true, a stream request with no known opaque-id mapping falls back to the most recent
     * video ID. This can mis-serve a file during prefetch/gapless transitions; see
     * registerStreamBytes() for the hardening hook.
     */
    private static final boolean FALLBACK_TO_CURRENT_ID = true;

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern OPAQUE_ID_IN_URL = Pattern.compile("[?&]id=(o-[A-Za-z0-9_-]+)");

    private static volatile String currentVideoId;
    private static final Map<String, String> opaqueIdToVideoId = new ConcurrentHashMap<>();

    // region Hooks called from patched bytecode

    /** Hook A: call when a new player response is parsed. */
    public static void setCurrentVideoId(String videoId) {
        currentVideoId = videoId;
    }

    /**
     * Optional hardening (not wired by default): pass the serialized streaming data for a video
     * so its googlevideo "id=o-..." token can be mapped to the real video ID. Removes the
     * prefetch race that FALLBACK_TO_CURRENT_ID has.
     */
    public static void registerStreamBytes(String videoId, byte[] streamingData) {
        if (videoId == null || streamingData == null) return;
        Matcher m = OPAQUE_ID_IN_URL.matcher(new String(streamingData, StandardCharsets.ISO_8859_1));
        while (m.find()) opaqueIdToVideoId.put(m.group(1), videoId);
    }

    /** Hook B: called with the stream Uri; returns it unchanged unless a local file applies. */
    public static Uri redirectToLocal(Uri original) {
        try {
            if (original == null) return null;
            String host = original.getHost();
            if (host == null || !host.endsWith("googlevideo.com")) return original;
            if (!"/videoplayback".equals(original.getPath())) return original;

            String opaqueId = original.getQueryParameter("id");
            String videoId = opaqueId == null ? null : opaqueIdToVideoId.get(opaqueId);
            if (videoId == null && FALLBACK_TO_CURRENT_ID) videoId = currentVideoId;
            if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) return original;

            String ext = extForItag(parseInt(original.getQueryParameter("itag")));
            if (ext == null) return original;
            if (findFile(videoId, ext) == null) return original;

            Uri.Builder b = new Uri.Builder()
                    .scheme("http")
                    .encodedAuthority("127.0.0.1:" + ensureServer())
                    .appendPath(TOKEN)
                    .appendPath(videoId + "." + ext);
            String range = original.getQueryParameter("range");
            if (range != null) b.appendQueryParameter("range", range);

            Log.i(TAG, "Serving local file for " + videoId + "." + ext);
            return b.build();
        } catch (Throwable t) {
            Log.e(TAG, "redirectToLocal failed", t);
            return original;
        }
    }

    // endregion

    // region File lookup

    private static final long CACHE_MS = 5000;
    private static volatile File[] cachedFiles;
    private static volatile long cacheTime;

    private static File getDir() {
        // ASSUMPTION: Morphe's shared extension exposes a static context getter like this.
        // Verify the class and method name in the extensions/shared module and adjust.
        Context c = app.morphe.extension.shared.Utils.getContext();
        return c == null ? null : c.getExternalFilesDir(SUBDIR);
    }

    private static File[] listFiles() {
        long now = System.currentTimeMillis();
        File[] cached = cachedFiles;
        if (cached != null && now - cacheTime < CACHE_MS) return cached;
        File dir = getDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) files = new File[0];
        cachedFiles = files;
        cacheTime = now;
        return files;
    }

    /** Matches yt-dlp style names: "Anything [VIDEOID].ext". */
    static File findFile(String videoId, String ext) {
        Pattern p = Pattern.compile(
                ".*\\[" + Pattern.quote(videoId) + "\\]\\." + Pattern.quote(ext) + "$",
                Pattern.CASE_INSENSITIVE);
        for (File f : listFiles()) {
            if (f.isFile() && p.matcher(f.getName()).matches()) return f;
        }
        return null;
    }

    /**
     * The file is only byte-compatible with the format it was downloaded as, so the extension
     * is chosen from the itag the player requested: 140 = AAC in MP4 (.m4a), 251 = Opus in
     * WebM (.webm). Anything else is streamed normally.
     */
    private static String extForItag(int itag) {
        if (itag == 140) return "m4a";
        if (itag == 251) return "webm";
        return null;
    }

    private static String mimeFor(String ext) {
        return "m4a".equals(ext) ? "audio/mp4" : "audio/webm";
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // endregion

    // region Loopback HTTP server

    private static final String TOKEN = randomToken();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LocalAudio-conn");
        t.setDaemon(true);
        return t;
    });
    private static int serverPort;

    private static String randomToken() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static synchronized int ensureServer() throws IOException {
        if (serverPort > 0) return serverPort;
        final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        serverPort = ss.getLocalPort();
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    POOL.execute(() -> handle(s));
                } catch (IOException e) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }, "LocalAudio-accept");
        t.setDaemon(true);
        t.start();
        return serverPort;
    }

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];

            String headerRange = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "range:", 0, 6)) headerRange = line.substring(6).trim();
            }

            OutputStream out = s.getOutputStream();
            Uri u = Uri.parse("http://localhost" + parts[1]);
            List<String> seg = u.getPathSegments();
            if (seg.size() != 2 || !TOKEN.equals(seg.get(0))) {
                writeStatusOnly(out, 403, "Forbidden");
                return;
            }
            String name = seg.get(1);
            int dot = name.indexOf('.');
            if (dot != 11) {
                writeStatusOnly(out, 404, "Not Found");
                return;
            }
            String videoId = name.substring(0, dot);
            String ext = name.substring(dot + 1);
            if (!VIDEO_ID.matcher(videoId).matches() || extForItagExt(ext) == null) {
                writeStatusOnly(out, 404, "Not Found");
                return;
            }
            File file = findFile(videoId, ext);
            if (file == null) {
                writeStatusOnly(out, 404, "Not Found");
                return;
            }

            long len = file.length();
            long start = 0, end = len - 1;
            boolean viaHeader = false, ranged = false;

            // googlevideo uses either a `range=a-b` query param or a standard Range header.
            String spec = u.getQueryParameter("range");
            if (spec == null && headerRange != null && headerRange.startsWith("bytes=")) {
                spec = headerRange.substring(6);
                viaHeader = true;
            }
            if (spec != null) {
                try {
                    int dash = spec.indexOf('-');
                    String a = spec.substring(0, dash), b = spec.substring(dash + 1);
                    if (a.isEmpty()) {
                        start = Math.max(0, len - Long.parseLong(b));
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(Long.parseLong(b), len - 1);
                    }
                    ranged = true;
                } catch (RuntimeException e) {
                    writeStatusOnly(out, 416, "Range Not Satisfiable");
                    return;
                }
                if (start > end || start >= len) {
                    writeStatusOnly(out, 416, "Range Not Satisfiable");
                    return;
                }
            }

            // Query-param ranges: plain 200 with the slice (believed to match googlevideo;
            // verify against captured traffic). Header ranges: proper 206.
            boolean partial = ranged && viaHeader;
            long count = end - start + 1;
            StringBuilder h = new StringBuilder()
                    .append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n")
                    .append("Content-Type: ").append(mimeFor(ext)).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n")
                    .append("Content-Length: ").append(count).append("\r\n");
            if (partial) {
                h.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(len).append("\r\n");
            }
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (!"HEAD".equals(method)) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(start);
                    byte[] buf = new byte[64 * 1024];
                    long remaining = count;
                    while (remaining > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // Client closed the connection (seek/skip); normal.
        } catch (Throwable t) {
            Log.e(TAG, "connection error", t);
        }
    }

    private static String extForItagExt(String ext) {
        return ("m4a".equals(ext) || "webm".equals(ext)) ? ext : null;
    }

    private static void writeStatusOnly(OutputStream out, int code, String text) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + text
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    // endregion
}
