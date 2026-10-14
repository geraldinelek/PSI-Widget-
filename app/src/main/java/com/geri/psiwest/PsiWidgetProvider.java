package com.geri.psiwest;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Home-screen widget showing Singapore West region readings from NEA via data.gov.sg:
 * 1-hr PM2.5 (main number, colour-coded by NEA band) and 24-hr PSI.
 * Refreshes hourly, and immediately when tapped.
 */
public class PsiWidgetProvider extends AppWidgetProvider {

    static final String ACTION_REFRESH = "com.geri.psiwest.REFRESH";

    private static final String PM25_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";
    private static final String PSI_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi";
    private static final String PREFS = "last_reading";

    private static final int[] TEXT_IDS = {
            R.id.label, R.id.value, R.id.unit, R.id.band, R.id.psi, R.id.updated
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refreshAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        refreshAll(context);
    }

    /** Ask any running widgets to refresh (used by the app screen). */
    static void requestRefresh(Context context) {
        Intent i = new Intent(context, PsiWidgetProvider.class).setAction(ACTION_REFRESH);
        context.sendBroadcast(i);
    }

    private void refreshAll(Context context) {
        final Context app = context.getApplicationContext();
        final AppWidgetManager manager = AppWidgetManager.getInstance(app);
        final int[] ids = manager.getAppWidgetIds(new ComponentName(app, PsiWidgetProvider.class));
        if (ids == null || ids.length == 0) return;

        RemoteViews loading = new RemoteViews(app.getPackageName(), R.layout.widget_psi);
        loading.setTextViewText(R.id.updated, app.getString(R.string.updating));
        manager.partiallyUpdateAppWidget(ids, loading);

        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                manager.updateAppWidget(ids, buildViews(app));
            } finally {
                pending.finish();
            }
        }).start();
    }

    private static RemoteViews buildViews(Context app) {
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_psi);
        v.setOnClickPendingIntent(R.id.root, refreshIntent(app));
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        try {
            JSONObject pmItem = firstItem(fetch(PM25_URL));
            int pm25 = pmItem.getJSONObject("readings")
                    .getJSONObject("pm25_one_hourly").getInt("west");
            String time = formatTime(pmItem.getString("timestamp"));

            String psiText;
            try {
                JSONObject psiItem = firstItem(fetch(PSI_URL));
                int psi = psiItem.getJSONObject("readings")
                        .getJSONObject("psi_twenty_four_hourly").getInt("west");
                psiText = "24-hr PSI " + psi + " · " + psiLabel(psi);
            } catch (Exception e) {
                psiText = "24-hr PSI unavailable";
            }

            prefs.edit()
                    .putInt("pm25", pm25)
                    .putString("psi", psiText)
                    .putString("time", time)
                    .apply();

            render(v, pm25, psiText, "As of " + time + " · tap to refresh");
        } catch (Exception e) {
            if (prefs.contains("pm25")) {
                render(v, prefs.getInt("pm25", 0), prefs.getString("psi", ""),
                        "Offline. Last " + prefs.getString("time", "") + " · tap to retry");
            } else {
                v.setTextViewText(R.id.value, "--");
                v.setTextViewText(R.id.band, "No data yet");
                v.setTextViewText(R.id.psi, "");
                v.setTextViewText(R.id.updated, "Couldn't connect · tap to retry");
                v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_neutral);
                setTextColour(v, 0xFFFFFFFF);
            }
        }
        return v;
    }

    private static void render(RemoteViews v, int pm25, String psiText, String updated) {
        int bg;
        int text = 0xFFFFFFFF;
        String band;
        if (pm25 <= 55) {
            bg = R.drawable.bg_band1;
            band = "Band I · Normal";
        } else if (pm25 <= 150) {
            bg = R.drawable.bg_band2;
            band = "Band II · Elevated";
            text = 0xFF1F1F1F;
        } else if (pm25 <= 250) {
            bg = R.drawable.bg_band3;
            band = "Band III · High";
        } else {
            bg = R.drawable.bg_band4;
            band = "Band IV · Very high";
        }
        v.setInt(R.id.root, "setBackgroundResource", bg);
        setTextColour(v, text);
        v.setTextViewText(R.id.value, String.valueOf(pm25));
        v.setTextViewText(R.id.band, band);
        v.setTextViewText(R.id.psi, psiText);
        v.setTextViewText(R.id.updated, updated);
    }

    private static void setTextColour(RemoteViews v, int colour) {
        for (int id : TEXT_IDS) v.setTextColor(id, colour);
    }

    private static String psiLabel(int psi) {
        if (psi <= 50) return "Good";
        if (psi <= 100) return "Moderate";
        if (psi <= 200) return "Unhealthy";
        if (psi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    private static String formatTime(String iso) {
        try {
            return OffsetDateTime.parse(iso)
                    .format(DateTimeFormatter.ofPattern("h:mm a, d MMM", Locale.ENGLISH));
        } catch (Exception e) {
            return iso;
        }
    }

    private static JSONObject firstItem(JSONObject root) throws Exception {
        return root.getJSONObject("data").getJSONArray("items").getJSONObject(0);
    }

    private static JSONObject fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private static PendingIntent refreshIntent(Context app) {
        Intent i = new Intent(app, PsiWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(app, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
