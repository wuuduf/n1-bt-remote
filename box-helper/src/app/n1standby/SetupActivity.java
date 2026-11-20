package app.n1standby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The helper's own screen on the box (D-pad friendly). Opening it once sets everything up;
 * after that it shows what is in place and offers to change the Phicomm remote or undo all.
 */
public final class SetupActivity extends Activity {

    private static final int OK = 0xFF30D158, BAD = 0xFFFF6961, NEUTRAL = 0xFFB0B0B5;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView rowAdb, rowService, rowPower, rowRemote, rowState, summary;
    private Button setupBtn, remoteBtn, removeBtn;
    private Setup.Status last;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
        rowAdb = findViewById(R.id.row_adb);
        rowService = findViewById(R.id.row_service);
        rowPower = findViewById(R.id.row_power);
        rowRemote = findViewById(R.id.row_remote);
        rowState = findViewById(R.id.row_state);
        summary = findViewById(R.id.summary);
        setupBtn = findViewById(R.id.btn_setup);
        remoteBtn = findViewById(R.id.btn_remote);
        removeBtn = findViewById(R.id.btn_remove);

        setupBtn.setOnClickListener(v -> runSetup());
        remoteBtn.setOnClickListener(v -> {
            boolean patch = last == null || !"TV".equals(last.remote);
            prefs.edit().putBoolean("patch_remote", patch).apply();
            work(() -> Setup.setRemotePatched(patch));
        });
        removeBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.remove_title)
                .setMessage(R.string.remove_message)
                .setPositiveButton(R.string.remove_confirm, (d, w) -> {
                    summary.setText(R.string.removing);
                    io.execute(Setup::restoreAndUninstall);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        runSetup(); // opening the app is all the setup a user should need
        setupBtn.requestFocus();
    }

    private void runSetup() {
        boolean patchRemote = prefs.getBoolean("patch_remote", true);
        work(() -> Setup.apply(patchRemote));
    }

    private void work(Supplier<Setup.Status> job) {
        summary.setText(R.string.working);
        summary.setTextColor(NEUTRAL);
        setButtonsEnabled(false);
        io.execute(() -> {
            Setup.Status s = job.get();
            // The system binds a newly enabled accessibility service within a moment.
            for (int i = 0; i < 10 && s.accessibility && !StandbyService.running; i++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
            main.post(() -> render(s));
        });
    }

    private void render(Setup.Status s) {
        last = s;
        setButtonsEnabled(true);
        row(rowAdb, s.adbOk, R.string.row_adb, s.adbOk ? getString(R.string.adb_ok, s.firmware) : getString(R.string.adb_bad));
        boolean svcRunning = s.accessibility && StandbyService.running;
        row(rowService, svcRunning, R.string.row_service, getString(svcRunning ? R.string.service_ok
                : s.accessibility ? R.string.service_starting : R.string.service_bad));
        row(rowPower, s.powerKeySafe, R.string.row_power, getString(s.powerKeySafe ? R.string.power_ok : R.string.power_bad));
        if ("none".equals(s.remote)) {
            rowRemote.setText(getString(R.string.row_remote) + "\n   " + getString(R.string.remote_none));
            rowRemote.setTextColor(NEUTRAL);
            remoteBtn.setEnabled(false);
        } else {
            boolean patched = "TV".equals(s.remote);
            row(rowRemote, patched, R.string.row_remote, getString(patched ? R.string.remote_ok : R.string.remote_off));
            remoteBtn.setText(patched ? R.string.btn_remote_restore : R.string.btn_remote_patch);
        }
        rowState.setText(getString(R.string.row_state) + "\n   "
                + getString(s.standby ? R.string.state_standby : R.string.state_awake));
        rowState.setTextColor(NEUTRAL);
        boolean ready = s.ready() && svcRunning;
        summary.setText(ready ? R.string.summary_ok : R.string.summary_bad);
        summary.setTextColor(ready ? OK : BAD);
        if (!s.adbOk) rowRemote.setText(getString(R.string.row_remote) + "\n   " + getString(R.string.unknown));
    }

    private void row(TextView v, boolean ok, int title, String detail) {
        v.setText((ok ? "✔  " : "✘  ") + getString(title) + "\n   " + detail);
        v.setTextColor(ok ? OK : BAD);
    }

    private void setButtonsEnabled(boolean on) {
        setupBtn.setEnabled(on);
        remoteBtn.setEnabled(on);
        removeBtn.setEnabled(on);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
