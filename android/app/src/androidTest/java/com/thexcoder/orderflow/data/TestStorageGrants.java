package com.thexcoder.orderflow.data;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Test-process helper: asks the separate provider owner to grant the target APK a tree URI. */
public final class TestStorageGrants {
    private TestStorageGrants() {}
    public static void grant(Context context, String treeUri) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Grant fixture off the main thread");
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(Activity.RESULT_CANCELED);
        AtomicReference<String> description = new AtomicReference<>("No grant response");
        Intent request = new Intent();
        request.setComponent(new ComponentName("com.thexcoder.orderflow.test", TestStorageGrantReceiver.class.getName()));
        request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        request.putExtra("treeUri", treeUri);
        context.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent resultIntent) {
                result.set(getResultCode());
                description.set(getResultData());
                done.countDown();
            }
        }, new Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null);
        try {
            if (!done.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out granting fixture URI");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted granting fixture URI", error);
        }
        if (result.get() != Activity.RESULT_OK) throw new IllegalStateException("Cannot grant fixture URI: " + description.get());
    }
}
