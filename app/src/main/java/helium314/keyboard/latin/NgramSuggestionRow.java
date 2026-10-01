package helium314.keyboard.latin;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.List;

/** SUPER_NGRAM_PATCH_V1 - lightweight second suggestion row. */
public final class NgramSuggestionRow extends HorizontalScrollView {
    public interface Listener {
        void onCandidate(String text);
        void onClipboardToggle();
        void onClipboardLearnNow();
    }

    private final LinearLayout strip;
    private Listener listener;
    private boolean clipOn;

    public NgramSuggestionRow(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        addView(strip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        render(List.of());
    }

    public void setListener(Listener l) { listener = l; }

    public void setClipboardOn(boolean value) {
        clipOn = value;
    }

    public void render(List<SuperNgramEngine.Candidate> items) {
        strip.removeAllViews();
        TextView clip = chip(clipOn ? "CLIP ON" : "CLIP OFF");
        clip.setTypeface(Typeface.DEFAULT_BOLD);
        clip.setOnClickListener(v -> { if (listener != null) listener.onClipboardToggle(); });
        clip.setOnLongClickListener(v -> {
            if (listener != null) listener.onClipboardLearnNow();
            return true;
        });
        strip.addView(clip);

        for (SuperNgramEngine.Candidate c : items) {
            TextView tv = chip(c.text);
            tv.setOnClickListener(v -> { if (listener != null) listener.onCandidate(c.text); });
            strip.addView(tv);
        }
    }

    private TextView chip(String text) {
        TextView v = new TextView(getContext());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER);
        int hp = dp(11), vp = dp(5);
        v.setPadding(hp, vp, hp, vp);
        v.setMinHeight(dp(34));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public static NgramSuggestionRow attach(Context context, View anchor) {
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) {
            return null;
        }

        /*
         * HeliBoard 4.1 structure:
         *
         * LinearLayout main_keyboard_frame
         *   -> FrameLayout strip_container
         *        -> SuggestionStripView
         *   -> KeyboardWrapperView
         *
         * We want the SuperNgram row BELOW strip_container and ABOVE
         * the keyboard, not overlaid inside the FrameLayout.
         */
        View target = anchor;
        ViewGroup p = (ViewGroup) anchor.getParent();

        if (p instanceof FrameLayout && p.getParent() instanceof ViewGroup) {
            target = p;
            p = (ViewGroup) p.getParent();
        }

        // Remove stale row when the IME input view is recreated.
        for (int i = p.getChildCount() - 1; i >= 0; i--) {
            View c = p.getChildAt(i);
            if (c instanceof NgramSuggestionRow) {
                p.removeViewAt(i);
            }
        }

        NgramSuggestionRow row = new NgramSuggestionRow(context);
        int h = Math.round(
                36 * context.getResources().getDisplayMetrics().density);

        if (p instanceof LinearLayout) {
            int idx = p.indexOfChild(target);
            if (idx < 0) return null;

            p.addView(
                    row,
                    idx + 1,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            h
                    )
            );
            return row;
        }

        if (p instanceof RelativeLayout && target.getId() != View.NO_ID) {
            RelativeLayout.LayoutParams lp =
                    new RelativeLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            h
                    );
            lp.addRule(RelativeLayout.BELOW, target.getId());
            p.addView(row, lp);
            return row;
        }

        return null;
    }
}