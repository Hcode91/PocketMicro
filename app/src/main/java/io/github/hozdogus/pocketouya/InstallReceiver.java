package io.github.hozdogus.pocketouya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the installer's progress: asks the user to confirm, then reports the result. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(confirm);
            }
            return;
        }
        String msg;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            msg = "Game installed – it's in your library now.";
        } else {
            String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            msg = "Install failed" + (detail != null ? ": " + detail : "")
                    + (status == PackageInstaller.STATUS_FAILURE_CONFLICT
                       ? "\nUninstall the original version of this game first." : "");
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        ctx.sendBroadcast(new Intent(MainActivity.ACTION_REFRESH).setPackage(ctx.getPackageName()));
    }
}
