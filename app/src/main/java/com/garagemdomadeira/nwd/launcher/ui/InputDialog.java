package com.garagemdomadeira.nwd.launcher.ui;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.R;

/** App-styled dialog with a text field (password, prop value, etc.). */
public final class InputDialog {
    private InputDialog() {
    }

    /** Receives the typed text. Returns an error message to keep the dialog open, or null to close it. */
    public interface OnSubmit {
        String submit(String text);
    }

    public static Dialog show(Activity activity, String title, String message, String initial,
                              boolean password, String okLabel, OnSubmit onSubmit) {
        Dialog d = new Dialog(activity, R.style.OverlayDialog);
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_input, null);
        Ui.applyFont(root);
        d.setContentView(root);
        ((TextView) root.findViewById(R.id.input_title)).setText(title);
        TextView msg = (TextView) root.findViewById(R.id.input_message);
        if (message == null || message.isEmpty()) msg.setVisibility(View.GONE);
        else msg.setText(message);
        EditText field = (EditText) root.findViewById(R.id.input_field);
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        if (initial != null) {
            field.setText(initial);
            field.setSelection(initial.length());
        }
        TextView error = (TextView) root.findViewById(R.id.input_error);
        TextView ok = (TextView) root.findViewById(R.id.input_ok);
        ok.setText(okLabel);
        ok.setActivated(true);
        Runnable submit = () -> {
            String err = onSubmit.submit(field.getText().toString());
            if (err == null) {
                d.dismiss();
            } else {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
            }
        };
        ok.setOnClickListener(v -> submit.run());
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (enter && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit.run();
                return true;
            }
            return false;
        });
        root.findViewById(R.id.input_cancel).setOnClickListener(v -> d.dismiss());
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        Ui.hideSystemBars(d.getWindow());
        d.show();
        field.requestFocus();
        Touch.install(root);
        return d;
    }
}
