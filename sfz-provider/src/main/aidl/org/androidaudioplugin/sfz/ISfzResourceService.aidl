package org.androidaudioplugin.sfz;
import android.os.Bundle;
import org.androidaudioplugin.sfz.ISfzResourceSession;
interface ISfzResourceService {
    int getProtocolVersion();
    Bundle[] listInstruments(int offset, int limit);
    ISfzResourceSession openInstrument(String instrumentId, String revision);
}
