package org.androidaudioplugin.sfz;
import android.content.res.AssetFileDescriptor;
interface ISfzResourceSession {
    String getEntryPath();
    String[] listResources(int offset, int limit);
    AssetFileDescriptor openResource(String name);
    void close();
}
