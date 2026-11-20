package com.thexcoder.orderflow.data;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Executes in the provider-owning test APK UID, which can issue genuine SAF grants. */
public final class TestStorageGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Uri tree = Uri.parse(intent.getStringExtra("treeUri"));
            if (!"content".equals(tree.getScheme()) || !TestDocumentsProvider.AUTHORITY.equals(tree.getAuthority()) ||
                !DocumentsContract.isTreeUri(tree)) throw new IllegalArgumentException("Unexpected fixture URI");
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (!id.equals("root") && !id.startsWith("root/")) throw new IllegalArgumentException("Invalid fixture root");
            context.grantUriPermission("com.thexcoder.orderflow", tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            setResultCode(Activity.RESULT_OK);
            setResultData("granted");
        } catch (Exception error) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData(error.toString());
        }
    }
}
