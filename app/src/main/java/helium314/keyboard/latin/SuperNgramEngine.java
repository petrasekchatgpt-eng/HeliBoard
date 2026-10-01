package helium314.keyboard.latin;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SUPER_NGRAM_PATCH_V1
 * Sparse offline 1..10-gram model. No network access.
 * All database operations must be called from a worker thread.
 */
public final class SuperNgramEngine {
    public static final int MAX_N = 10;
    private static final String SEP = "\u241f";
    private static final double MIN_SCORE = 0.05d;
    private static final double HALF_LIFE_DAYS = 45.0d;
    private static final int CACHE_MAX = 768;

    // Keeps words, Unicode indexes, math/syntax symbols and technical fragments.
    private static final Pattern TOKEN = Pattern.compile(
            "%[^\\s%]+%<[^\\s>]*>|@[\\p{L}\\p{N}_.]+|" +
            "[\\p{L}\\p{N}_]+[₀₁₂₃₄₅₆₇₈₉⁰¹²³⁴⁵⁶⁷⁸⁹]*|" +
            "[Σ∑∏√∫∂∇∞≤≥≠→←↔±≈≡]+|[^\\s]"
    );

    public static final class Candidate {
        public final String text;
        public final double score;
        Candidate(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    private static final class Hit {
        final String token;
        final double freq;
        final long last;
        Hit(String token, double freq, long last) {
            this.token = token; this.freq = freq; this.last = last;
        }
    }

    private final DB db;
    private final LinkedHashMap<String, List<Hit>> cache =
            new LinkedHashMap<String, List<Hit>>(CACHE_MAX, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Hit>> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    public SuperNgramEngine(Context context) {
        db = new DB(context.getApplicationContext());
    }

    public static List<String> tokenize(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static String prefixOf(String beforeCursor) {
        if (beforeCursor == null || beforeCursor.isEmpty()) return "";
        int i = beforeCursor.length();
        while (i > 0) {
            int cp = beforeCursor.codePointBefore(i);
            if (Character.isWhitespace(cp)) break;
            // delimiters that should terminate a word-like prefix
            if (cp == '\n' || cp == '\r' || cp == '\t') break;
            i -= Character.charCount(cp);
        }
        return beforeCursor.substring(i);
    }

    public static boolean hasCommitBoundary(String s) {
        if (s == null || s.isEmpty()) return false;
        int cp = s.codePointBefore(s.length());
        return Character.isWhitespace(cp) ||
                ".;,!?)]}>:".indexOf(cp) >= 0;
    }

    public List<Candidate> predict(List<String> context, String prefix, int limit) {
        final long start = SystemClock.elapsedRealtimeNanos();
        final HashMap<String, Double> merged = new HashMap<>();
        final String safePrefix = prefix == null ? "" : prefix;

        for (int n = MAX_N; n >= 1; n--) {
            int need = n - 1;
            if (context.size() < need) continue;
            List<String> tail = need == 0
                    ? Collections.emptyList()
                    : context.subList(context.size() - need, context.size());
            String ctx = join(tail);
            List<Hit> hits = lookup(n, ctx, safePrefix, 20);
            double weight = Math.scalb(1.0d, n - 1); // 1..512
            for (Hit h : hits) {
                double s = weight * decayed(h.freq, h.last);
                Double old = merged.get(h.token);
                if (old == null || s > old) merged.put(h.token, s);
            }
            // High-context result found: don't burn time scanning every lower n.
            if (n >= 4 && merged.size() >= limit) break;
        }

        // Vocabulary prefix fallback.
        if (merged.size() < limit && !safePrefix.isEmpty()) {
            for (Hit h : lookupVocab(safePrefix, 24)) {
                double s = decayed(h.freq, h.last);
                Double old = merged.get(h.token);
                if (old == null || s > old) merged.put(h.token, s);
            }
        }

        ArrayList<Candidate> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : merged.entrySet()) {
            out.add(new Candidate(e.getKey(), e.getValue()));
        }
        out.sort((a,b) -> Double.compare(b.score, a.score));
        if (out.size() > limit) out.subList(limit, out.size()).clear();
        return out;
    }

    public List<Candidate> phrasePredict(List<String> context, String prefix, int limit) {
        List<Candidate> first = predict(context, prefix, Math.min(6, limit));
        ArrayList<Candidate> out = new ArrayList<>(first);
        int beams = Math.min(3, first.size());
        for (int i = 0; i < beams; i++) {
            Candidate c = first.get(i);
            ArrayList<String> cctx = new ArrayList<>(context);
            cctx.add(c.text);
            StringBuilder phrase = new StringBuilder(c.text);
            double score = c.score;
            for (int step = 0; step < 3; step++) {
                List<Candidate> next = predict(cctx, "", 1);
                if (next.isEmpty()) break;
                Candidate nx = next.get(0);
                phrase.append(' ').append(nx.text);
                cctx.add(nx.text);
                score += nx.score * 0.15d;
            }
            if (phrase.indexOf(" ") > 0) out.add(new Candidate(phrase.toString(), score * .9d));
        }
        out.sort((a,b) -> Double.compare(b.score, a.score));
        // de-duplicate
        LinkedHashMap<String, Candidate> uniq = new LinkedHashMap<>();
        for (Candidate c : out) uniq.putIfAbsent(c.text, c);
        ArrayList<Candidate> result = new ArrayList<>(uniq.values());
        if (result.size() > limit) result.subList(limit, result.size()).clear();
        return result;
    }

    public void learnCommitted(List<String> previous, String token) {
        if (token == null || token.isBlank()) return;
        final long now = System.currentTimeMillis();
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.execSQL("INSERT INTO vocab(token,freq,last) VALUES(?,1,?) " +
                    "ON CONFLICT(token) DO UPDATE SET freq=freq+1,last=excluded.last",
                    new Object[]{token, now});

            for (int n = 1; n <= MAX_N; n++) {
                int need = n - 1;
                if (previous.size() < need) continue;
                List<String> tail = need == 0
                        ? Collections.emptyList()
                        : previous.subList(previous.size() - need, previous.size());
                String ctx = join(tail);
                long h = fnv1a64(ctx);
                w.execSQL("INSERT INTO grams(n,h,ctx,next,freq,last) VALUES(?,?,?,?,1,?) " +
                        "ON CONFLICT(n,h,ctx,next) DO UPDATE SET freq=freq+1,last=excluded.last",
                        new Object[]{n, h, ctx, token, now});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        synchronized (cache) { cache.clear(); }
    }

    public void learnCorpus(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return;
        ArrayList<String> prev = new ArrayList<>();
        for (String t : tokens) {
            learnCommitted(prev, t);
            prev.add(t);
            if (prev.size() > MAX_N - 1) prev.remove(0);
        }
    }

    private double decayed(double freq, long last) {
        double ageDays = Math.max(0d, (System.currentTimeMillis() - last) / 86400000d);
        double factor = Math.exp(-Math.log(2d) * ageDays / HALF_LIFE_DAYS);
        return MIN_SCORE + Math.max(0d, freq - MIN_SCORE) * factor;
    }

    private List<Hit> lookup(int n, String ctx, String prefix, int limit) {
        String ck = n + "\u0001" + ctx + "\u0001" + prefix + "\u0001" + limit;
        synchronized (cache) {
            List<Hit> c = cache.get(ck);
            if (c != null) return c;
        }

        ArrayList<Hit> out = new ArrayList<>();
        SQLiteDatabase r = db.getReadableDatabase();
        try (Cursor c = r.rawQuery(
                "SELECT next,freq,last FROM grams " +
                "WHERE n=? AND h=? AND ctx=? AND next LIKE ? ESCAPE '\\' " +
                "ORDER BY freq DESC LIMIT ?",
                new String[]{
                        Integer.toString(n),
                        Long.toString(fnv1a64(ctx)),
                        ctx,
                        escapeLike(prefix) + "%",
                        Integer.toString(limit)
                })) {
            while (c.moveToNext()) out.add(new Hit(c.getString(0), c.getDouble(1), c.getLong(2)));
        }
        List<Hit> frozen = Collections.unmodifiableList(out);
        synchronized (cache) { cache.put(ck, frozen); }
        return frozen;
    }

    private List<Hit> lookupVocab(String prefix, int limit) {
        ArrayList<Hit> out = new ArrayList<>();
        SQLiteDatabase r = db.getReadableDatabase();
        try (Cursor c = r.rawQuery(
                "SELECT token,freq,last FROM vocab WHERE token LIKE ? ESCAPE '\\' " +
                "ORDER BY freq DESC LIMIT ?",
                new String[]{escapeLike(prefix) + "%", Integer.toString(limit)})) {
            while (c.moveToNext()) out.add(new Hit(c.getString(0), c.getDouble(1), c.getLong(2)));
        }
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String join(List<String> xs) {
        if (xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(x);
        }
        return sb.toString();
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public void close() { db.close(); }

    private static final class DB extends SQLiteOpenHelper {
        DB(Context c) { super(c, "super_ngram_v1.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("PRAGMA journal_mode=WAL");
            d.execSQL("CREATE TABLE grams(" +
                    "n INTEGER NOT NULL,h INTEGER NOT NULL,ctx TEXT NOT NULL,next TEXT NOT NULL," +
                    "freq REAL NOT NULL,last INTEGER NOT NULL," +
                    "PRIMARY KEY(n,h,ctx,next))");
            d.execSQL("CREATE INDEX grams_lookup ON grams(n,h,ctx,freq DESC)");
            d.execSQL("CREATE TABLE vocab(token TEXT PRIMARY KEY,freq REAL NOT NULL,last INTEGER NOT NULL)");
            d.execSQL("CREATE INDEX vocab_freq ON vocab(freq DESC)");
        }
        @Override public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {}
    }
}
