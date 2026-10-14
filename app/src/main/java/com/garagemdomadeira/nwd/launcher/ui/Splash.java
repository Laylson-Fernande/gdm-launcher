package com.garagemdomadeira.nwd.launcher.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.R;

/**
 * Short "Opening RADIO" card (service icon + name) over the screen content, above the dock,
 * before a dock screen opens. Besides the feedback, it is the time players get to stop before a
 * source change: the NWD system force-stops our package if the source leaves Android while our
 * MP3 player still holds audio focus.
 */
public final class Splash {
    private Splash() {
    }

    public static final long DURATION_MS = 1000;
    /** Keeps the card up a little after starting the next screen, so the old one doesn't flash. */
    private static final long REMOVE_AFTER_MS = 400;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View current;
    private static Runnable pending;

    /** Shows the card for {@code item} for DURATION_MS, then runs {@code then}. */
    public static void show(final Activity activity, DockView.Item item, final Runnable then) {
        cancel();
        final ViewGroup stage = stageOf(activity);
        final View card = build(activity, item);
        stage.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(150).start();
        current = card;
        pending = () -> {
            pending = null;
            if (!activity.isFinishing() && !activity.isDestroyed()) then.run();
            main.postDelayed(() -> remove(card), REMOVE_AFTER_MS);
        };
        main.postDelayed(pending, DURATION_MS);
    }

    /** Drops a card still waiting (e.g. another dock item was tapped meanwhile). */
    public static void cancel() {
        if (pending != null) main.removeCallbacks(pending);
        pending = null;
        if (current != null) remove(current);
    }

    private static void remove(View card) {
        ViewGroup parent = (ViewGroup) card.getParent();
        if (parent != null) parent.removeView(card);
        if (current == card) current = null;
    }

    /** The screen content above the dock (see Screens.attachDock); the whole window otherwise. */
    private static ViewGroup stageOf(Activity activity) {
        View stage = activity.findViewById(R.id.screen_stage);
        if (stage instanceof ViewGroup) return (ViewGroup) stage;
        return (ViewGroup) activity.findViewById(android.R.id.content);
    }

    private static View build(Activity a, DockView.Item item) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundColor(Ui.BG);
        card.setClickable(true); // swallows taps on the screen underneath while it is up

        int iconSize = Math.round(Ui.dp(a, 112));
        IconView icon = new IconView(a).set(item.icon(a), IconView.Style.NONE).color(Ui.ACCENT);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = Math.round(Ui.dp(a, 32));
        card.addView(text, textLp);

        TextView opening = new TextView(a);
        opening.setText(R.string.screen_opening);
        opening.setTextColor(Ui.TEXT_SOFT);
        opening.setTextSize(26);
        opening.setTypeface(Ui.regular(a));
        opening.setIncludeFontPadding(false);
        text.addView(opening);

        TextView name = new TextView(a);
        name.setText(item.label(a));
        name.setTextColor(Ui.TEXT);
        name.setTextSize(52);
        name.setTypeface(Ui.semiBold(a));
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Math.round(Ui.dp(a, 8));
        text.addView(name, nameLp);
        return card;
    }
}
