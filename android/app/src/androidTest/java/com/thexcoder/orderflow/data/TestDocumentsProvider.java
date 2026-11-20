package com.thexcoder.orderflow.data;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Local SAF provider packaged only in the instrumentation APK. Deliberately Java-only:
 * a provider has its own process/classloader, outside the target's Kotlin runtime.
 */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.thexcoder.orderflow.tests.documents";
    private static final String[] DEFAULT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    private File base() {
        File root = new File(getContext().getCacheDir(), "saf-test-root");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create fixture root");
        return root;
    }

    @Override public boolean onCreate() { return true; }

    private File file(String documentId) throws FileNotFoundException {
        File root = base();
        if (!documentId.equals("root") && !documentId.startsWith("root/")) {
            throw new FileNotFoundException("Invalid document id");
        }
        File candidate = documentId.equals("root") ? root : new File(root, documentId.substring(5));
        try {
            String path = candidate.getCanonicalPath();
            String basePath = root.getCanonicalPath();
            if (!path.equals(basePath) && !path.startsWith(basePath + File.separator)) {
                throw new FileNotFoundException("Document escapes fixture root");
            }
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return candidate;
    }

    private String id(File target) {
        String root = base().getAbsolutePath();
        String path = target.getAbsolutePath();
        return path.equals(root) ? "root" : "root/" + path.substring(root.length() + 1);
    }

    private void documentRow(MatrixCursor cursor, File target) {
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id(target));
        values.put(Document.COLUMN_DISPLAY_NAME, target.getName());
        values.put(Document.COLUMN_MIME_TYPE, target.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        values.put(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME |
            (target.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE));
        values.put(Document.COLUMN_SIZE, target.length());
        values.put(Document.COLUMN_LAST_MODIFIED, target.lastModified());
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(values.get(column));
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "test");
        values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "OrderFlow Test SAF");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(values.get(column));
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_COLUMNS);
        File target = file(documentId);
        if (!target.exists()) throw new FileNotFoundException(documentId);
        documentRow(cursor, target);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
        throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_COLUMNS);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) for (File child : children) documentRow(cursor, child);
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName)
        throws FileNotFoundException {
        if (displayName.contains("/") || displayName.equals(".") || displayName.equals("..")) {
            throw new FileNotFoundException("Unsafe fixture name");
        }
        File target = new File(file(parentDocumentId), displayName);
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mimeType) ? target.mkdir() : target.createNewFile();
            if (!created) throw new FileNotFoundException("Fixture already exists: " + displayName);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return id(target);
    }

    private boolean deleteTree(File target) {
        File[] children = target.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return target.delete();
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (!deleteTree(file(documentId))) throw new FileNotFoundException("Cannot delete fixture");
    }

    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (displayName.contains("/") || displayName.equals(".") || displayName.equals("..")) {
            throw new FileNotFoundException("Unsafe fixture name");
        }
        File target = file(documentId);
        File destination = new File(target.getParentFile(), displayName);
        if (destination.exists() || !target.renameTo(destination)) throw new FileNotFoundException("Cannot rename fixture");
        return id(destination);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return file(documentId).getCanonicalPath().startsWith(file(parentDocumentId).getCanonicalPath() + File.separator);
        } catch (IOException error) { return false; }
    }
}
