package cmccwm.mobilemusic;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DiagnosticLog {
    private static final String PREFS = "diagnostic_log";
    private static final String KEY = "entries";
    private static final int MAX_CHARS = 12000;

    private DiagnosticLog() { }

    static synchronized void add(Context context, String event) {
        if (context == null || event == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String previous = preferences.getString(KEY, "");
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String next = previous + time + "  " + event.replace('\n', ' ') + "\n";
        if (next.length() > MAX_CHARS) next = next.substring(next.length() - MAX_CHARS);
        preferences.edit().putString(KEY, next).apply();
    }

    static synchronized String dump(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "（暂无诊断事件）\n");
    }
}
