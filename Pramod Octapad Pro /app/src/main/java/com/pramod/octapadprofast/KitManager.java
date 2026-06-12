package com.pramod.octapadpromidi;

import android.content.Context;
import android.net.Uri;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public class KitManager {
    public static final int PAD_COUNT = 8;
    public static final String[] DEFAULT_WAV_NAMES = {
        "crash.wav", "tom.wav", "rim.wav", "clap.wav",
        "kick.wav", "snare.wav", "ohat.wav", "chat.wav"
    };

    public static void saveMachineCfgToUri(Context context, Uri cfgUri) {
        try {
            OutputStream os = context.getContentResolver().openOutputStream(cfgUri);
            ObjectOutputStream oos = new ObjectOutputStream(os);
            oos.writeObject(new String[PAD_COUNT]); // Placeholder for names
            oos.writeObject(new float[PAD_COUNT]);  // Placeholder for volume
            oos.close();
        } catch (Exception e) { e.printStackTrace(); }
    }
}

