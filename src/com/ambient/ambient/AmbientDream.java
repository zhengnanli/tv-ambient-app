package com.ambient.ambient;

import android.content.Intent;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.webkit.WebView;

public class AmbientDream extends DreamService {
    private WebView web;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive only so OK can be told apart from other keys; see dispatchKeyEvent.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        web = Dashboard.create(this, false, false);
        setContentView(web);
    }

    /** OK opens the app with its settings menu up; any other key just ends the screensaver. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (ok) {
                startActivity(new Intent(this, PreviewActivity.class)
                        .putExtra(PreviewActivity.EXTRA_SETTINGS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            }
            finish();
        }
        return true;
    }

    @Override
    public void onDetachedFromWindow() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDetachedFromWindow();
    }
}
