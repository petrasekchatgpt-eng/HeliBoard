package helium314.keyboard.latin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SUPER_NGRAM_PATCH_V1
 * Controller deliberately lives beside, not inside, HeliBoard's original dictionaries.
 */
public final class NgramController implements NgramSuggestionRow.Listener {
    private static final int CONTEXT_CHARS = 1024;
    private static final int CLIP_MAX_CHARS = 65536;

    private final LatinIME service;
    private final SuperNgramEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HB-10gram-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final ClipboardManager clipboard;
    private final AtomicLong generation = new AtomicLong();
    private volatile NgramSuggestionRow row;
    private volatile boolean learningAllowed = true;
    private volatile String lastLearnSignature = "";
    private volatile int lastPrefixChars = 0;

    private final Runnable refreshRunnable = this::refreshNow;

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener;

    public NgramController(LatinIME service) {
        this.service = service;
        this.engine = new SuperNgramEngine(service);
        this.prefs = service.getSharedPreferences("super_ngram_prefs", Context.MODE_PRIVATE);
        this.clipboard = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);

        this.clipListener = () -> {
            if (prefs.getBoolean("clipboard_auto_learning", false) && learningAllowed) {
                learnClipboardAsync(false);
            }
        };

        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
    }

    public void attachRow(NgramSuggestionRow r) {
        row = r;
        if (r != null) {
            r.setListener(this);
            r.setClipboardOn(prefs.getBoolean("clipboard_auto_learning", false));
        }
    }

    public void onStartInput(EditorInfo info) {
        learningAllowed = !isSensitive(info);
        lastLearnSignature = "";
        scheduleRefresh();
    }

    public void onFinishInput() {
        ui.removeCallbacks(refreshRunnable);
        generation.incrementAndGet();
    }

    public void scheduleRefresh() {
        ui.removeCallbacks(refreshRunnable);
        ui.postDelayed(refreshRunnable, 18L); // debounce; UI thread never performs DB queries.
    }

    private void refreshNow() {
        final InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) return;
        CharSequence seq;
        try {
            seq = ic.getTextBeforeCursor(CONTEXT_CHARS, 0);
        } catch (Throwable t) {
            return;
        }
        if (seq == null) return;

        final String text = seq.toString();
        final long gen = generation.incrementAndGet();
        worker.execute(() -> {
            try {
                LearnAndContext lc = splitContext(text);

                // First-use learning happens only after a boundary, never on every keystroke.
                if (learningAllowed && SuperNgramEngine.hasCommitBoundary(text) && lc.committedToken != null) {
                    String sig = signature(lc.previousForLearning, lc.committedToken);
                    if (!sig.equals(lastLearnSignature)) {
                        engine.learnCommitted(lc.previousForLearning, lc.committedToken);
                        lastLearnSignature = sig;
                    }
                }

                lastPrefixChars = lc.prefix.length();
                List<SuperNgramEngine.Candidate> out =
                        engine.phrasePredict(lc.contextForPrediction, lc.prefix, 8);

                if (generation.get() != gen) return;
                ui.post(() -> {
                    NgramSuggestionRow r = row;
                    if (r != null) {
                        r.setClipboardOn(prefs.getBoolean("clipboard_auto_learning", false));
                        r.render(out);
                    }
                });
            } catch (Throwable ignored) {
                // Never let the experimental predictor crash the IME.
            }
        });
    }

    private static LearnAndContext splitContext(String beforeCursor) {
        String prefix = SuperNgramEngine.prefixOf(beforeCursor);
        String committedPart = beforeCursor;
        if (!prefix.isEmpty() && beforeCursor.endsWith(prefix)) {
            committedPart = beforeCursor.substring(0, beforeCursor.length() - prefix.length());
        }

        List<String> committed = SuperNgramEngine.tokenize(committedPart);
        ArrayList<String> prediction = new ArrayList<>(committed);
        if (prediction.size() > 9) {
            prediction = new ArrayList<>(prediction.subList(prediction.size() - 9, prediction.size()));
        }

        String committedToken = committed.isEmpty() ? null : committed.get(committed.size() - 1);
        ArrayList<String> previous = new ArrayList<>();
        if (committed.size() > 1) {
            int from = Math.max(0, committed.size() - 10);
            previous.addAll(committed.subList(from, committed.size() - 1));
        }
        return new LearnAndContext(prediction, previous, committedToken, prefix);
    }

    private static final class LearnAndContext {
        final List<String> contextForPrediction;
        final List<String> previousForLearning;
        final String committedToken;
        final String prefix;
        LearnAndContext(List<String> c, List<String> p, String t, String x) {
            contextForPrediction = c; previousForLearning = p; committedToken = t; prefix = x;
        }
    }

    private static String signature(List<String> prev, String token) {
        return Integer.toHexString(Objects.hash(prev, token));
    }

    private static boolean isSensitive(EditorInfo info) {
        if (info == null) return true;
        int klass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;

        if (klass == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true;
        }
        if (klass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true;

        // Android flag explicitly telling IMEs not to personalize.
        return android.os.Build.VERSION.SDK_INT >= 26 &&
                (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
    }

    @Override public void onCandidate(String text) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null || text == null) return;
        try {
            int n = Math.max(0, lastPrefixChars);
            if (n > 0) ic.deleteSurroundingText(n, 0);
            ic.commitText(text, 1);
            scheduleRefresh();
        } catch (Throwable ignored) {}
    }

    @Override public void onClipboardToggle() {
        boolean next = !prefs.getBoolean("clipboard_auto_learning", false);
        prefs.edit().putBoolean("clipboard_auto_learning", next).apply();
        NgramSuggestionRow r = row;
        if (r != null) r.setClipboardOn(next);
        Toast.makeText(service, "Clipboard learning: " + (next ? "ON" : "OFF"),
                Toast.LENGTH_SHORT).show();
        if (next && learningAllowed) learnClipboardAsync(false);
    }

    @Override public void onClipboardLearnNow() {
        learnClipboardAsync(true);
    }

    private void learnClipboardAsync(boolean toast) {
        if (!learningAllowed || clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData cd = clipboard.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence cs = cd.getItemAt(0).coerceToText(service);
        if (cs == null) return;
        String text = cs.toString();
        if (text.length() > CLIP_MAX_CHARS) text = text.substring(0, CLIP_MAX_CHARS);
        final String corpus = text;
        worker.execute(() -> {
            engine.learnCorpus(corpus);
            if (toast) ui.post(() ->
                    Toast.makeText(service, "Clipboard naučen", Toast.LENGTH_SHORT).show());
            scheduleRefresh();
        });
    }

    public void close() {
        ui.removeCallbacksAndMessages(null);
        if (clipboard != null) {
            try { clipboard.removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {}
        }
        worker.shutdown();
        engine.close();
    }
}
