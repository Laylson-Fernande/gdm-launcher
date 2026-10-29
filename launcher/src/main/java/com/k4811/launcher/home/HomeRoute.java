package com.k4811.launcher.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.k4811.common.Apps;
import com.k4811.common.home.NavApps;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.R;
import com.k4811.map.NavProgress;
import com.k4811.map.TurnIcon;

/**
 * Rota na tela inicial, acima da hora (bloco do relógio): a próxima manobra, a distância até ela, a
 * rua e a chegada. Vem do mapa integrado ({@link NavProgress}, mesmo processo) ou do app de navegação
 * externo (o Waze modificado publica {@link Apps#ACTION_NAV_STATE}; ao aparecer, esta tela pergunta
 * com {@link Apps#ACTION_NAV_QUERY}). Escuta só enquanto a tela inicial está na frente.
 */
public final class HomeRoute implements NavProgress.Listener {

    private final Activity activity;
    private final HomeModuleViews views;
    private final View iconBox;
    private final ImageView icon;
    private final TurnIcon turn;
    private final TextView distance;
    private final TextView cue;
    private final TextView eta;
    private boolean started;
    private boolean shown;
    /** Último NAV_STATE ativo do app externo; null = sem rota nele. */
    private Intent external;

    private final BroadcastReceiver navState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String pkg = NavApps.chosen(activity);
            if (pkg == null || !pkg.equals(i.getStringExtra("app"))) return;
            external = i.getBooleanExtra("active", false) ? i : null;
            render();
        }
    };

    public HomeRoute(Activity activity, HomeModuleViews views) {
        this.activity = activity;
        this.views = views;
        iconBox = activity.findViewById(R.id.home_route_icon_box);
        icon = (ImageView) activity.findViewById(R.id.home_route_icon);
        turn = (TurnIcon) activity.findViewById(R.id.home_route_turn);
        distance = (TextView) activity.findViewById(R.id.home_route_distance);
        cue = (TextView) activity.findViewById(R.id.home_route_cue);
        eta = (TextView) activity.findViewById(R.id.home_route_eta);
        distance.setTypeface(Ui.semiBold(activity));
        cue.setTypeface(Ui.medium(activity));
        eta.setTypeface(Ui.regular(activity));
        turn.setColor(Ui.TEXT);
    }

    /** A tela inicial apareceu: escuta as rotas e pede a do app externo. */
    public void start() {
        if (started) return;
        started = true;
        NavProgress.addListener(this);
        activity.registerReceiver(navState, new IntentFilter(Apps.ACTION_NAV_STATE));
        String pkg = NavApps.chosen(activity);
        if (pkg != null) activity.sendBroadcast(new Intent(Apps.ACTION_NAV_QUERY).setPackage(pkg));
        render();
    }

    public void stop() {
        if (!started) return;
        started = false;
        NavProgress.removeListener(this);
        try {
            activity.unregisterReceiver(navState);
        } catch (RuntimeException ignored) {
            // já saiu
        }
    }

    /** Há rota acima da hora (um toque no relógio abre a tela de navegação). */
    public boolean shown() {
        return shown;
    }

    @Override
    public void onNavProgress(NavProgress.State s) {
        render();
    }

    /** O mapa integrado tem prioridade (é a navegação desta central); senão, o app externo. */
    private void render() {
        NavProgress.State integrated = NavProgress.current();
        boolean show = integrated != null || external != null;
        if (show != shown) {
            shown = show;
            views.setRouteShown(show);
        }
        if (!show) return;
        if (integrated != null) {
            iconBox.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            turn.setVisibility(View.VISIBLE);
            turn.setShape(integrated.shape);
            set(distance, integrated.distance);
            set(cue, integrated.cue);
            set(eta, integrated.eta);
            return;
        }
        Intent i = external;
        turn.setVisibility(View.GONE);
        Bitmap b = i.getParcelableExtra("icon");
        icon.setImageBitmap(b);
        icon.setColorFilter(Ui.TEXT);
        icon.setVisibility(View.VISIBLE);
        iconBox.setVisibility(b == null ? View.GONE : View.VISIBLE);
        set(distance, i.getStringExtra("distance"));
        set(cue, i.getStringExtra("cue"));
        StringBuilder line = new StringBuilder();
        String arrival = i.getStringExtra("arrival");
        if (arrival != null) line.append(activity.getString(R.string.home_nav_arrival, arrival));
        for (String part : new String[]{i.getStringExtra("remaining"), i.getStringExtra("remaining_distance")}) {
            if (part == null) continue;
            if (line.length() > 0) line.append("  ·  ");
            line.append(part);
        }
        set(eta, line.toString());
    }

    /** setText só quando muda; texto vazio esconde a linha. */
    private static void set(TextView v, String text) {
        String t = text == null ? "" : text;
        if (!t.contentEquals(v.getText())) v.setText(t);
        v.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
