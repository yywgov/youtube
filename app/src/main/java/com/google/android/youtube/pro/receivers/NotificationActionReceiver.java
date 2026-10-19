package com.google.android.youtube.pro.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.youtube.pro.PlaybackService;

/** Explicit (non-exported) receiver for the media notification buttons. */
public class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        PlaybackService.handleAction(context, intent.getAction());
    }
}
