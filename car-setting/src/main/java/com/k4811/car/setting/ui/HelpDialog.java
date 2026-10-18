package com.k4811.car.setting.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.car.setting.R;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

/** Janela com o texto de ajuda (blockquote) de uma opção das configurações. */
public final class HelpDialog {
    private HelpDialog() {
    }

    public static Dialog show(Activity activity, String title, String body) {
        Dialog d = new Dialog(activity, R.style.OverlayDialog);
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_help, null);
        Ui.applyFont(root);
        d.setContentView(root);
        TextView titleView = (TextView) root.findViewById(R.id.help_title);
        titleView.setText(title);
        titleView.setTypeface(Ui.semiBold(activity));
        TextView bodyView = (TextView) root.findViewById(R.id.help_body);
        bodyView.setText(body);
        bodyView.setTypeface(Ui.medium(activity));
        root.findViewById(R.id.help_ok).setOnClickListener(v -> d.dismiss());
        root.setOnClickListener(v -> d.dismiss());
        root.findViewById(R.id.help_panel).setOnClickListener(v -> { /* consome o toque */ });

        final ScrollView scroll = (ScrollView) bodyView.getParent();
        scroll.post(() -> {
            int maxH = Math.round(Ui.dp(activity, 360));
            if (scroll.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = maxH;
                scroll.setLayoutParams(lp);
            }
        });

        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Ui.hideSystemBars(d.getWindow());
        d.show();
        Touch.install(root);
        return d;
    }
}
