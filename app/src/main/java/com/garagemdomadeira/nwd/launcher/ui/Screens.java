package com.garagemdomadeira.nwd.launcher.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.garagemdomadeira.nwd.launcher.AppsActivity;
import com.garagemdomadeira.nwd.launcher.BtMusicActivity;
import com.garagemdomadeira.nwd.launcher.EqActivity;
import com.garagemdomadeira.nwd.launcher.HomeActivity;
import com.garagemdomadeira.nwd.launcher.MusicActivity;
import com.garagemdomadeira.nwd.launcher.PhoneActivity;
import com.garagemdomadeira.nwd.launcher.R;
import com.garagemdomadeira.nwd.launcher.RadioActivity;
import com.garagemdomadeira.nwd.launcher.system.ExternalApps;

/**
 * Places the dock below a screen's content and handles navigation between dock screens.
 * Navigation: from the home screen it opens the screen; from one dock screen to another, it
 * replaces the current one (Back always returns home and screens don't stack).
 */
public final class Screens {
    private Screens() {
    }

    public static Class<? extends Activity> screenOf(DockView.Item item) {
        switch (item) {
            case HOME:
                return HomeActivity.class;
            case RADIO:
                return RadioActivity.class;
            case MUSIC:
                return MusicActivity.class;
            case PHONE:
                return PhoneActivity.class;
            case BT_MUSIC:
                return BtMusicActivity.class;
            case EQ:
                return EqActivity.class;
            default:
                return AppsActivity.class;
        }
    }

    /**
     * Call right after setContentView. The content takes the space above the dock.
     * @param current this screen's item (highlighted); null on the home screen and non-dock screens
     */
    public static DockView attachDock(Activity activity, DockView.Item current) {
        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        View content = root.getChildAt(0);
        root.removeView(content);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Ui.BG);
        column.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        DockView dock = new DockView(activity);
        column.addView(dock, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(Ui.dp(activity, DockView.HEIGHT_DP))));
        root.addView(column, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dock.setActive(current);
        dock.setListener(item -> navigate(activity, current, item));
        return dock;
    }

    public static void navigate(Activity from, DockView.Item current, DockView.Item target) {
        if (target == current) return;
        if (target.isExternal()) {
            // Head-unit app: opens on top, the current screen stays where it is.
            if (target == DockView.Item.AUX) ExternalApps.openAux(from);
            else if (target == DockView.Item.VIDEO) ExternalApps.openVideo(from);
            else ExternalApps.open360(from);
            return;
        }
        if (target == DockView.Item.HOME) {
            if (from instanceof HomeActivity) return;
            // Return to the home screen (which sits beneath the dock screens) with the close animation.
            Intent home = new Intent(from, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            from.startActivity(home);
            from.finish();
            from.overridePendingTransition(R.anim.screen_close_enter, R.anim.screen_close_exit);
            return;
        }
        Intent i = new Intent(from, screenOf(target));
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(i);
        if (!(from instanceof HomeActivity)) {
            // Switch between dock screens: quick crossfade (the dock stays in place).
            from.finish();
            from.overridePendingTransition(R.anim.screen_swap_in, R.anim.screen_swap_out);
        }
    }
}
