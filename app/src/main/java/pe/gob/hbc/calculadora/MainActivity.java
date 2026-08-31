package pe.gob.hbc.calculadora;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 4107;
    private static final int HBC_FOLDER_REQUEST = 4108;
    private static final String PREFS = "hbc_drive";
    private static final String TREE_URI_KEY = "tree_uri";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "No se pudo abrir el selector de archivos.",
                            Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == HBC_FOLDER_REQUEST) {
            boolean linked = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(treeUri, flags);
                    prefs().edit().putString(TREE_URI_KEY, treeUri.toString()).apply();
                    linked = true;
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo guardar el permiso de la carpeta.", Toast.LENGTH_LONG).show();
                }
            }
            final boolean selected = linked;
            webView.post(() -> webView.evaluateJavascript(
                    "window.onHbcFolderSelected && window.onHbcFolderSelected(" + selected + ");", null));
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void saveText(String fileName, String data, String mimeType) {
            runOnUiThread(() -> saveToDownloads(fileName, data, mimeType));
        }

        @JavascriptInterface
        public void chooseHbcFolder() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(intent, HBC_FOLDER_REQUEST);
            });
        }

        @JavascriptInterface
        public String hbcFolderInfo() {
            try {
                Uri tree = savedTreeUri();
                if (tree == null) return result(false, "No hay carpeta vinculada.", null).toString();
                JSONObject value = new JSONObject();
                value.put("name", displayName(documentUri(tree)));
                return result(true, "", value).toString();
            } catch (Exception e) {
                return result(false, "No se puede acceder a la carpeta vinculada.", null).toString();
            }
        }

        @JavascriptInterface
        public String listHbcSources() {
            try {
                Uri tree = savedTreeUri();
                if (tree == null) return result(false, "Primero vincule CALCULADORA HBC.", null).toString();
                Uri root = documentUri(tree);
                String rootName = displayName(root);
                if (!"CALCULADORA HBC".equals(normalize(rootName))) {
                    return result(false, "Seleccione exactamente la carpeta CALCULADORA HBC.", null).toString();
                }
                JSONArray sources = new JSONArray();
                collectSources(root, "", sources);
                JSONObject value = new JSONObject();
                value.put("rootName", rootName);
                value.put("sources", sources);
                return result(true, "", value).toString();
            } catch (Exception e) {
                return result(false, "No se pudo leer Drive: " + e.getMessage(), null).toString();
            }
        }

        @JavascriptInterface
        public String readHbcSource(String uriText) {
            try {
                Uri uri = Uri.parse(uriText);
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("Archivo no disponible");
                    byte[] bytes = readAll(in);
                    JSONObject value = new JSONObject();
                    value.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    return result(true, "", value).toString();
                }
            } catch (Exception e) {
                return result(false, "No se pudo leer el archivo: " + e.getMessage(), null).toString();
            }
        }

        @JavascriptInterface
        public String writeHbcOperations(String json) {
            try {
                Uri tree = savedTreeUri();
                if (tree == null) return result(false, "No hay carpeta vinculada.", null).toString();
                Uri root = documentUri(tree);
                if (!"CALCULADORA HBC".equals(normalize(displayName(root)))) {
                    return result(false, "La carpeta vinculada no es CALCULADORA HBC.", null).toString();
                }
                Uri loans = findChild(root, "DATA PRESTAMOS", true);
                if (loans == null) loans = DocumentsContract.createDocument(getContentResolver(), root,
                        DocumentsContract.Document.MIME_TYPE_DIR, "DATA PRESTAMOS");
                if (loans == null) throw new IllegalStateException("No se pudo crear DATA PRESTAMOS");
                Uri operations = findChild(loans, "operaciones_remuneraciones.json", false);
                if (operations == null) operations = DocumentsContract.createDocument(getContentResolver(), loans,
                        "application/json", "operaciones_remuneraciones.json");
                if (operations == null) throw new IllegalStateException("No se pudo crear el historial");
                try (OutputStream out = getContentResolver().openOutputStream(operations, "wt")) {
                    if (out == null) throw new IllegalStateException("No se pudo abrir el historial");
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                return result(true, "", null).toString();
            } catch (Exception e) {
                return result(false, "No se pudo actualizar el historial compartido: " + e.getMessage(), null).toString();
            }
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private Uri savedTreeUri() {
        String value = prefs().getString(TREE_URI_KEY, null);
        return value == null ? null : Uri.parse(value);
    }

    private Uri documentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return "";
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private Uri findChild(Uri parent, String name, boolean directory) {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String childName = cursor.getString(1);
                String mime = cursor.getString(2);
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (isDir == directory && name.equalsIgnoreCase(childName)) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0));
                }
            }
        }
        return null;
    }

    private void collectSources(Uri parent, String relativePath, JSONArray output) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(parent, id);
                String path = relativePath.isEmpty() ? name : relativePath + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectSources(child, path, output);
                    continue;
                }
                String group = sourceGroup(path, name);
                if (group == null) continue;
                JSONObject item = new JSONObject();
                item.put("id", child.toString());
                item.put("name", name);
                item.put("path", path);
                item.put("group", group);
                item.put("modified", cursor.isNull(3) ? 0 : cursor.getLong(3));
                item.put("size", cursor.isNull(4) ? 0 : cursor.getLong(4));
                output.put(item);
            }
        }
    }

    private String sourceGroup(String path, String name) {
        String p = normalize(path).replace('\\', '/');
        String n = normalize(name);
        boolean sheet = n.endsWith(".XLSX") || n.endsWith(".XLSM") || n.endsWith(".XLS") || n.endsWith(".DBF");
        if (p.startsWith("DATA PLH/CAS/") && sheet) return "plh-cas";
        if (p.startsWith("DATA PLH/NOMBRADOS/") && sheet) return "plh-nombrados";
        if (p.startsWith("DATA BANCOS/") && (n.endsWith(".PRN") || n.endsWith(".TXT"))) return "banks";
        if (p.startsWith("DATA CAFAE - EXCEL/") && sheet) return "cafae-excel";
        if (p.equals("DATA PRESTAMOS/OPERACIONES_REMUNERACIONES.JSON")) return "operations";
        return null;
    }

    private byte[] readAll(InputStream in) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private JSONObject result(boolean ok, String error, JSONObject value) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", ok);
            if (error != null && !error.isEmpty()) result.put("error", error);
            if (value != null) result.put("value", value);
        } catch (Exception ignored) { }
        return result;
    }

    private void saveToDownloads(String fileName, String data, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE,
                    (mimeType == null || mimeType.isEmpty()) ? "application/json" : mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Calculadora HBC");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = getContentResolver().insert(collection, values);
            if (item == null) throw new IllegalStateException("No se pudo crear el archivo");

            try (OutputStream out = getContentResolver().openOutputStream(item)) {
                if (out == null) throw new IllegalStateException("No se pudo escribir el archivo");
                out.write(data.getBytes(StandardCharsets.UTF_8));
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(item, values, null, null);
            Toast.makeText(this,
                    "Guardado en Descargas/Calculadora HBC/" + fileName,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudo guardar: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}

