package com.pramod.octapadpromidi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int PAD_COUNT = 8;
    private static final int MAX_KITS = 50;

    private static final int REQ_PICK_SINGLE_WAV = 5001;
    private static final int REQ_SAVE_FOLDER = 2001;
    private static final int REQ_LOAD_FOLDER = 2002;
    private static final int REQ_LIST_FOLDER = 2003;

    private Button[] pads = new Button[PAD_COUNT];
    private TextView txtKitName, txtSelectedPad;
    private Button btnEditMode, btnSaveKit, btnLoadKit, btnRenameKit, btnPrevKit, btnNextKit, btnEq;
    private SeekBar seekVolume, seekPitch;
    
    private View fxControlBar, advControlBar;
    private CheckBox chkDelay;
    private SeekBar seekDelayTime, seekDelayLevel, seekEqHigh, seekEqMid, seekEqLow, seekChokeGroup;

    private Uri[] selectedWavUris = new Uri[PAD_COUNT];
    private int[] selectedRawResIds = new int[PAD_COUNT];
    private float[] padVolume = new float[PAD_COUNT];
    private float[] padPitch = new float[PAD_COUNT];

    private boolean[] padDelayOn = new boolean[PAD_COUNT];
    private float[] padDelayTime = new float[PAD_COUNT];
    private float[] padDelayLevel = new float[PAD_COUNT];
    private float[] padEqHigh = new float[PAD_COUNT];
    private float[] padEqMid = new float[PAD_COUNT];
    private float[] padEqLow = new float[PAD_COUNT];
    private int[] padChokeGroup = new int[PAD_COUNT];

    private int selectedPad = 0;
    private boolean editMode = false;

    private int kitIndex = 1;
    private String currentKitName = "KIT 1";
    private String pendingSaveKitName = null;

    private int copySourcePad = -1;
    private int swapSourcePad = -1;

    private AudioEngine audioEngine;
    private AudioEngine.SampleData[] samples = new AudioEngine.SampleData[PAD_COUNT];
    private Uri assistSoundUri;
    private AudioEngine.SampleData assistSoundId;
    private int[] activePointerId = new int[PAD_COUNT];

    private int currentPresetKit = 0;

    private MidiManager midiManager;
    private MidiDevice openedMidiDevice;
    private MidiOutputPort midiOutputPort;

    private final String[] presetKitNames = new String[25];
    private final int[][] presetKits = new int[25][PAD_COUNT];

    private void initPresets() {
        presetKitNames[0] = "Intro Patch";
        presetKitNames[1] = "Dadra Kaharwa";
        presetKitNames[2] = "Duff Patch";
        presetKitNames[3] = "Kaharwa Dadra Manjira";
        presetKitNames[4] = "Deepchandi Patch";
        presetKitNames[5] = "Bhanda Huk Patch";
        presetKitNames[6] = "Disco Patch";
        presetKitNames[7] = "Dholak Manjira Patch";
        presetKitNames[8] = "Dhumal Patch";
        presetKitNames[9] = "Gaura Gauri Patch";
        presetKitNames[10] = "Tiger Dhumal Patch";
        presetKitNames[11] = "Groomer Patch";
        presetKitNames[12] = "Dandiya Patch";
        presetKitNames[13] = "CG Patch";
        presetKitNames[14] = "Jasgeet Manjira Patch";
        presetKitNames[15] = "Jasgeet Jhanj Patch";
        presetKitNames[16] = "CG Sambalpuri";
        presetKitNames[17] = "Panthi Patch";
        presetKitNames[18] = "Nagpuri Patch";
        presetKitNames[19] = "Percussion Patch";
        presetKitNames[20] = "Aana N Gori Ab";
        presetKitNames[21] = "Chham Chham Baje Patch";
        presetKitNames[22] = "CG Slow Karma Patch";
        presetKitNames[23] = "CG Karma Patch";
        presetKitNames[24] = "Drum Set Western Patch";

        for (int i = 0; i < 25; i++) {
            String suffix = (i == 0) ? "" : String.valueOf(i + 1);
            presetKits[i] = new int[]{
                getResources().getIdentifier("crash" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("tom" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("rim" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("clap" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("kick" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("snare" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("ohat" + suffix, "raw", getPackageName()),
                getResources().getIdentifier("chat" + suffix, "raw", getPackageName())
            };
        }
    }

    // ⚡ FAST PLAY: तेज़ रोलिंग के लिए ब्लॉक टाइम 5ms किया
    private long[] lastHitTime = new long[PAD_COUNT];
    private static final long HIT_BLOCK_MS = 5;

    private SharedPreferences prefs;
    private static final String PREF_NAME = "OctapadSettings";
    private static final String KEY_EDIT_MODE = "edit_mode";
    private static final String KEY_KIT_INDEX = "kit_index";
    private static final String KEY_LAST_LIST_FOLDER_URI = "last_list_folder_uri";

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        android.view.View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void setupMidi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            midiManager = (MidiManager) getSystemService(Context.MIDI_SERVICE);
            if (midiManager == null) return;

            MidiDeviceInfo[] infos = midiManager.getDevices();
            for (MidiDeviceInfo info : infos) {
                openMidiDevice(info);
            }

            midiManager.registerDeviceCallback(new MidiManager.DeviceCallback() {
                @Override
                public void onDeviceAdded(MidiDeviceInfo device) {
                    openMidiDevice(device);
                }

                @Override
                public void onDeviceRemoved(MidiDeviceInfo device) {
                    if (openedMidiDevice != null && openedMidiDevice.getInfo().getId() == device.getId()) {
                        closeMidiDevice();
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    private void openMidiDevice(MidiDeviceInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (info.getOutputPortCount() > 0) {
                midiManager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
                    @Override
                    public void onDeviceOpened(MidiDevice device) {
                        openedMidiDevice = device;
                        midiOutputPort = device.openOutputPort(0);
                        if (midiOutputPort != null) {
                            midiOutputPort.connect(new MidiReceiver() {
                                @Override
                                public void onSend(byte[] msg, int offset, int count, long timestamp) {
                                    if (count >= 3) {
                                        byte status = (byte) (msg[offset] & 0xF0);
                                        if (status == (byte) 0x90) { // Note On
                                            byte note = msg[offset + 1];
                                            byte velocity = msg[offset + 2];
                                            if (velocity > 0) {
                                                handleMidiNoteOn(note, velocity);
                                            }
                                        }
                                    }
                                }
                            });
                        }
                    }
                }, new Handler(Looper.getMainLooper()));
            }
        }
    }

    private void closeMidiDevice() {
        try {
            if (midiOutputPort != null) {
                midiOutputPort.close();
                midiOutputPort = null;
            }
            if (openedMidiDevice != null) {
                openedMidiDevice.close();
                openedMidiDevice = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMidiNoteOn(byte note, byte velocity) {
        int padIndex = -1;
        switch (note) {
            case 49: padIndex = 0; break; // crash
            case 45: case 47: case 48: case 50: padIndex = 1; break; // tom
            case 37: padIndex = 2; break; // rim
            case 39: padIndex = 3; break; // clap
            case 36: padIndex = 4; break; // kick
            case 38: case 40: padIndex = 5; break; // snare
            case 46: padIndex = 6; break; // ohat
            case 42: case 44: padIndex = 7; break; // chat
        }
        
        if (padIndex == -1) {
            padIndex = note % 8;
        }
        
        final int finalPadIndex = padIndex;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                playPadSound(finalPadIndex);
                pads[finalPadIndex].setPressed(true);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pads[finalPadIndex].setPressed(false);
                    }
                }, 100);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        hideSystemUI();

        initPresets();
        setupMidi();

        // REMOVE SYSTEM TOUCH SOUND
        getWindow().getDecorView().setSoundEffectsEnabled(false);

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        txtKitName = (TextView) findViewById(R.id.txtKitName);
        txtSelectedPad = (TextView) findViewById(R.id.txtSelectedPad);

        btnEditMode = (Button) findViewById(R.id.btnEditMode);
        btnSaveKit = (Button) findViewById(R.id.btnSaveKit);
        btnLoadKit = (Button) findViewById(R.id.btnLoadKit);
        btnRenameKit = (Button) findViewById(R.id.btnRenameKit);
        btnPrevKit = (Button) findViewById(R.id.btnPrevKit);
        btnNextKit = (Button) findViewById(R.id.btnNextKit);
        btnEq = (Button) findViewById(R.id.btnEq);

        seekVolume = (SeekBar) findViewById(R.id.seekVolume);
        seekPitch = (SeekBar) findViewById(R.id.seekPitch);

        fxControlBar = findViewById(R.id.fxControlBar);
        advControlBar = findViewById(R.id.advControlBar);
        chkDelay = (CheckBox) findViewById(R.id.chkDelay);
        seekDelayTime = (SeekBar) findViewById(R.id.seekDelayTime);
        seekDelayLevel = (SeekBar) findViewById(R.id.seekDelayLevel);
        seekEqHigh = (SeekBar) findViewById(R.id.seekEqHigh);
        seekEqMid = (SeekBar) findViewById(R.id.seekEqMid);
        seekEqLow = (SeekBar) findViewById(R.id.seekEqLow);
        seekChokeGroup = (SeekBar) findViewById(R.id.seekChokeGroup);

        audioEngine = new AudioEngine(this);
        audioEngine.start();

        initPads();
        initSeekBars();

        editMode = prefs.getBoolean(KEY_EDIT_MODE, false);
        kitIndex = prefs.getInt(KEY_KIT_INDEX, 1);
        if (kitIndex < 1) kitIndex = 1;

        loadKitFromMemory(kitIndex);
        updateEditButtonUI();

        btnEditMode.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editMode = !editMode;
                    if (!editMode) {
                        copySourcePad = -1;
                        swapSourcePad = -1;
                    }
                    updateEditButtonUI();
                    prefs.edit().putBoolean(KEY_EDIT_MODE, editMode).apply();
                    saveKitToMemory(kitIndex);
                }
            });

        btnRenameKit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    renameKitDialog();
                }
            });

        btnPrevKit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (kitIndex > 1) {
                        saveKitToMemory(kitIndex);
                        kitIndex--;
                        prefs.edit().putInt(KEY_KIT_INDEX, kitIndex).apply();
                        loadKitFromMemory(kitIndex);
                    } else {
                        Toast.makeText(MainActivity.this, "Already First Kit!", Toast.LENGTH_SHORT).show();
                    }
                }
            });

        btnNextKit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (kitIndex < MAX_KITS) {
                        saveKitToMemory(kitIndex);
                        kitIndex++;
                        prefs.edit().putInt(KEY_KIT_INDEX, kitIndex).apply();
                        loadKitFromMemory(kitIndex);
                    } else {
                        Toast.makeText(MainActivity.this, "Max Kit Limit Reached!", Toast.LENGTH_SHORT).show();
                    }
                }
            });

        btnLoadKit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, REQ_LOAD_FOLDER);
                }
            });

        btnSaveKit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSaveKitNameDialog();
                }
            });

        if (btnEq != null) {
            btnEq.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fxControlBar != null && advControlBar != null) {
                        if (fxControlBar.getVisibility() == View.VISIBLE) {
                            fxControlBar.setVisibility(View.GONE);
                            advControlBar.setVisibility(View.GONE);
                            btnEq.setBackgroundResource(R.drawable.btn_3d_dark); // Reset to original dark
                        } else {
                            fxControlBar.setVisibility(View.VISIBLE);
                            advControlBar.setVisibility(View.VISIBLE);
                            btnEq.setBackgroundResource(R.drawable.btn_3d_orange); // Highlight when active
                        }
                    }
                }
            });
        }
    }

    private void initPads() {
        int[] padIds = {
            R.id.pad1, R.id.pad2, R.id.pad3, R.id.pad4,
            R.id.pad5, R.id.pad6, R.id.pad7, R.id.pad8
        };

        for (int i = 0; i < PAD_COUNT; i++) {
            pads[i] = (Button) findViewById(padIds[i]);
            padVolume[i] = 0.80f;
            padPitch[i] = 1.0f;
            
            padDelayOn[i] = false;
            padDelayTime[i] = 150.0f;
            padDelayLevel[i] = 0.5f;
            padEqHigh[i] = 0.0f;
            padEqMid[i] = 0.0f;
            padEqLow[i] = 0.0f;
            
            activePointerId[i] = -1;
            lastHitTime[i] = 0;

            pads[i].setSoundEffectsEnabled(false);
            pads[i].setHapticFeedbackEnabled(false);
            pads[i].setClickable(true);
            pads[i].setLongClickable(false);
            pads[i].setFocusable(false);
            pads[i].setFocusableInTouchMode(false);
            pads[i].setOnClickListener(null);
            pads[i].setOnTouchListener(new PadTouch(i));
        }
    }

    private void initSeekBars() {
        seekVolume.setMax(100);
        seekPitch.setMax(100); // XML के साथ 100 पर सिंक किया गया

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    padVolume[selectedPad] = progress / 100.0f;
                    saveKitToMemory(kitIndex);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    padPitch[selectedPad] = 0.5f + (progress / 100.0f);
                    saveKitToMemory(kitIndex);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

        chkDelay.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                padDelayOn[selectedPad] = isChecked;
                saveKitToMemory(kitIndex);
            }
        });

        seekDelayTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padDelayTime[selectedPad] = progress; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        seekDelayLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padDelayLevel[selectedPad] = progress / 100.0f; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        seekEqHigh.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padEqHigh[selectedPad] = (progress - 100) * 0.15f; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        seekEqMid.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padEqMid[selectedPad] = (progress - 100) * 0.15f; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        seekEqLow.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padEqLow[selectedPad] = (progress - 100) * 0.15f; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        seekChokeGroup.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { padChokeGroup[selectedPad] = progress; saveKitToMemory(kitIndex); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void updateEditButtonUI() {
        btnEditMode.setText(editMode ? "EDIT ON" : "EDIT OFF");
        btnEditMode.setBackgroundResource(editMode ? R.drawable.btn_3d_red : R.drawable.btn_3d_dark);
    }

    private void playPadSound(int index) {
        if (samples[index] == null) {
            Toast.makeText(this, "No WAV Selected!", Toast.LENGTH_SHORT).show();
            return;
        }
        audioEngine.playSample(index, samples[index], padVolume[index], padPitch[index], 0,
                padDelayOn[index], padDelayTime[index], padDelayLevel[index],
                padEqLow[index], padEqMid[index], padEqHigh[index],
                padChokeGroup[index], 0.0f, 0.0f);
    }

    // ===================== ⚡ LOW LATENCY MULTI TOUCH PAD =====================
    private class PadTouch implements View.OnTouchListener {
        int index;
        PadTouch(int i) { index = i; }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            int pointerIndex = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIndex);

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (activePointerId[index] != -1) return true;

                long now = System.currentTimeMillis();
                if (now - lastHitTime[index] < HIT_BLOCK_MS) {
                    return true;
                }
                lastHitTime[index] = now;

                activePointerId[index] = pointerId;
                v.setPressed(true);

                // ⚡ लो-लेटेंसी फिक्स: टच होते ही सबसे पहले आवाज बजेगी
                if (!editMode) {
                    playPadSound(index);
                }

                selectedPad = index;

                if (editMode && copySourcePad != -1 && copySourcePad != index) {
                    copyPadSound(copySourcePad, index);
                    copySourcePad = -1;
                    saveKitToMemory(kitIndex);
                    return true;
                }

                if (editMode && swapSourcePad != -1 && swapSourcePad != index) {
                    swapPadSound(swapSourcePad, index);
                    swapSourcePad = -1;
                    saveKitToMemory(kitIndex);
                    return true;
                }

                if (editMode) {
                    showEditPadOptions(index);
                }

                // UI का काम बैकग्राउंड बफर में बाद में होगा
                txtSelectedPad.setText("Selected: PAD " + (index + 1));
                seekVolume.setProgress((int) (padVolume[index] * 100));
                seekPitch.setProgress((int) ((padPitch[index] - 0.5f) * 100));
                
                chkDelay.setChecked(padDelayOn[index]);
                seekDelayTime.setProgress((int) padDelayTime[index]);
                seekDelayLevel.setProgress((int) (padDelayLevel[index] * 100));
                seekEqHigh.setProgress((int) (padEqHigh[index] / 0.15f) + 100);
                seekEqMid.setProgress((int) (padEqMid[index] / 0.15f) + 100);
                seekEqLow.setProgress((int) (padEqLow[index] / 0.15f) + 100);
                seekChokeGroup.setProgress(padChokeGroup[index]);

                return true;
            }
            else if (action == MotionEvent.ACTION_UP
                     || action == MotionEvent.ACTION_POINTER_UP
                     || action == MotionEvent.ACTION_CANCEL) {

                if (activePointerId[index] == pointerId) {
                    activePointerId[index] = -1;
                    v.setPressed(false);
                }
                return true;
            }
            return false;
        }
    }

    // ===================== ⚡ YOUR ORIGINAL LOAD/SAVE SYSTEM RE-STORED =====================
    private void showEditPadOptions(final int padIndex) {
        String copyText = (copySourcePad == -1) ? "Pad Sound Copy (Select Source)" : "Pad Sound Copy (Paste Mode ON)";
        String swapText = (swapSourcePad == -1) ? "Pad Sound Exchange (Select First Pad)" : "Pad Sound Exchange (Swap Mode ON)";
        String[] options = { "Pad Select Sound", copyText, swapText, "Clear Pad Sound" };

        new AlertDialog.Builder(this)
            .setTitle("PAD " + (padIndex + 1) + " - EDIT OPTIONS")
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("audio/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(intent, REQ_PICK_SINGLE_WAV);
                    }
                    else if (which == 1) {
                        copySourcePad = padIndex;
                        swapSourcePad = -1;
                        Toast.makeText(MainActivity.this, "Copy Mode ON: Now tap target PAD to paste", Toast.LENGTH_SHORT).show();
                    }
                    else if (which == 2) {
                        swapSourcePad = padIndex;
                        copySourcePad = -1;
                        Toast.makeText(MainActivity.this, "Exchange Mode ON: Now tap second PAD to swap", Toast.LENGTH_SHORT).show();
                    }
                    else if (which == 3) {
                        selectedWavUris[padIndex] = null;
                        selectedRawResIds[padIndex] = 0;
                        samples[padIndex] = null;
                        padVolume[padIndex] = 0.80f;
                        padPitch[padIndex] = 1.0f;
                        
                        padDelayOn[padIndex] = false;
                        padDelayTime[padIndex] = 150.0f;
                        padDelayLevel[padIndex] = 0.5f;
                        padEqHigh[padIndex] = 0.0f;
                        padEqMid[padIndex] = 0.0f;
                        padEqLow[padIndex] = 0.0f;
                        padChokeGroup[padIndex] = 0;

                        saveKitToMemory(kitIndex);
                        Toast.makeText(MainActivity.this, "PAD " + (padIndex + 1) + " Cleared!", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void copyPadSound(int fromPad, int toPad) {
        if (fromPad == toPad) return;
        selectedWavUris[toPad] = selectedWavUris[fromPad];
        selectedRawResIds[toPad] = selectedRawResIds[fromPad];
        padVolume[toPad] = padVolume[fromPad];
        padPitch[toPad] = padPitch[fromPad];

        padDelayOn[toPad] = padDelayOn[fromPad];
        padDelayTime[toPad] = padDelayTime[fromPad];
        padDelayLevel[toPad] = padDelayLevel[fromPad];
        padEqHigh[toPad] = padEqHigh[fromPad];
        padEqMid[toPad] = padEqMid[fromPad];
        padEqLow[toPad] = padEqLow[fromPad];
        padChokeGroup[toPad] = padChokeGroup[fromPad];

        if (selectedWavUris[toPad] != null) {
            samples[toPad] = audioEngine.loadWavFromUri(toPad, selectedWavUris[toPad]);
        } else if (selectedRawResIds[toPad] != 0) {
            samples[toPad] = audioEngine.loadRawSound(toPad, selectedRawResIds[toPad]);
        } else {
            samples[toPad] = null;
        }

        saveKitToMemory(kitIndex);
        Toast.makeText(this, "Copied PAD " + (fromPad + 1) + " -> PAD " + (toPad + 1), Toast.LENGTH_SHORT).show();
    }

    private void swapPadSound(int padA, int padB) {
        if (padA == padB) return;

        Uri tempUri = selectedWavUris[padA];
        selectedWavUris[padA] = selectedWavUris[padB];
        selectedWavUris[padB] = tempUri;

        int tempRaw = selectedRawResIds[padA];
        selectedRawResIds[padA] = selectedRawResIds[padB];
        selectedRawResIds[padB] = tempRaw;

        float tempVol = padVolume[padA];
        padVolume[padA] = padVolume[padB];
        padVolume[padB] = tempVol;

        float tempPitch = padPitch[padA];
        padPitch[padA] = padPitch[padB];
        padPitch[padB] = tempPitch;

        boolean tempDlyOn = padDelayOn[padA];
        padDelayOn[padA] = padDelayOn[padB];
        padDelayOn[padB] = tempDlyOn;
        float tempDlyT = padDelayTime[padA];
        padDelayTime[padA] = padDelayTime[padB];
        padDelayTime[padB] = tempDlyT;
        float tempDlyL = padDelayLevel[padA];
        padDelayLevel[padA] = padDelayLevel[padB];
        padDelayLevel[padB] = tempDlyL;
        float tempEqH = padEqHigh[padA];
        padEqHigh[padA] = padEqHigh[padB];
        padEqHigh[padB] = tempEqH;
        float tempEqM = padEqMid[padA];
        padEqMid[padA] = padEqMid[padB];
        padEqMid[padB] = tempEqM;
        float tempEqL = padEqLow[padA];
        padEqLow[padA] = padEqLow[padB];
        padEqLow[padB] = tempEqL;
        int tempChoke = padChokeGroup[padA];
        padChokeGroup[padA] = padChokeGroup[padB];
        padChokeGroup[padB] = tempChoke;

        if (selectedWavUris[padA] != null) {
            samples[padA] = audioEngine.loadWavFromUri(padA, selectedWavUris[padA]);
        } else if (selectedRawResIds[padA] != 0) {
            samples[padA] = audioEngine.loadRawSound(padA, selectedRawResIds[padA]);
        } else {
            samples[padA] = null;
        }

        if (selectedWavUris[padB] != null) {
            samples[padB] = audioEngine.loadWavFromUri(padB, selectedWavUris[padB]);
        } else if (selectedRawResIds[padB] != 0) {
            samples[padB] = audioEngine.loadRawSound(padB, selectedRawResIds[padB]);
        } else {
            samples[padB] = null;
        }

        saveKitToMemory(kitIndex);
        Toast.makeText(this, "Swapped PAD " + (padA + 1) + " <-> PAD " + (padB + 1), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try {
            if (requestCode == REQ_PICK_SINGLE_WAV) {
                final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);

                selectedWavUris[selectedPad] = uri;
                samples[selectedPad] = audioEngine.loadWavFromUri(selectedPad, uri);

                if (samples[selectedPad] != null) {
                    audioEngine.preloadSample(samples[selectedPad]);
                }

                saveKitToMemory(kitIndex);
                Toast.makeText(this, "Sound Loaded & Saved!", Toast.LENGTH_SHORT).show();
            }
            else if (requestCode == REQ_LOAD_FOLDER) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                loadKitFromFolder(uri);
                saveKitToMemory(kitIndex);
            }
            else if (requestCode == REQ_SAVE_FOLDER) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (pendingSaveKitName != null && pendingSaveKitName.length() > 0) {
                    currentKitName = pendingSaveKitName;
                    txtKitName.setText(currentKitName);
                }
                saveKitToFolder(uri);
                pendingSaveKitName = null;
            }
            else if (requestCode == REQ_LIST_FOLDER) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString(KEY_LAST_LIST_FOLDER_URI, uri.toString()).apply();
                showKitListDialog(uri);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Permission Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showSaveKitNameDialog() {
        final EditText edt = new EditText(this);
        edt.setHint("Enter Kit Name");
        edt.setText(currentKitName);

        new AlertDialog.Builder(this)
            .setTitle("Save Kit As")
            .setView(edt)
            .setPositiveButton("NEXT", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = edt.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(MainActivity.this, "Kit name required!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    name = sanitizeFileName(name);
                    pendingSaveKitName = name;
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_FOLDER);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void renameKitDialog() {
        final EditText edt = new EditText(this);
        edt.setText(currentKitName);

        new AlertDialog.Builder(this)
            .setTitle("Enter Kit Name")
            .setView(edt)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    currentKitName = edt.getText().toString().trim();
                    if (currentKitName.length() == 0) currentKitName = "KIT " + kitIndex;
                    txtKitName.setText(currentKitName);
                    saveKitToMemory(kitIndex);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String sanitizeFileName(String name) {
        return name.replace("/", "_").replace("\\", "_").replace(":", "_")
            .replace("*", "_").replace("?", "_").replace("\"", "_")
            .replace("<", "_").replace(">", "_").replace("|", "_");
    }

    private void saveKitToMemory(int kitNo) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("kit_name_" + kitNo, currentKitName);

        for (int i = 0; i < PAD_COUNT; i++) {
            editor.putFloat("kit_" + kitNo + "_vol_" + i, padVolume[i]);
            editor.putFloat("kit_" + kitNo + "_pitch_" + i, padPitch[i]);

            editor.putBoolean("kit_" + kitNo + "_dlyon_" + i, padDelayOn[i]);
            editor.putFloat("kit_" + kitNo + "_dlyt_" + i, padDelayTime[i]);
            editor.putFloat("kit_" + kitNo + "_dlyl_" + i, padDelayLevel[i]);
            editor.putFloat("kit_" + kitNo + "_eqh_" + i, padEqHigh[i]);
            editor.putFloat("kit_" + kitNo + "_eqm_" + i, padEqMid[i]);
            editor.putFloat("kit_" + kitNo + "_eql_" + i, padEqLow[i]);
            editor.putInt("kit_" + kitNo + "_choke_" + i, padChokeGroup[i]);

            if (selectedWavUris[i] != null) {
                editor.putString("kit_" + kitNo + "_uri_" + i, selectedWavUris[i].toString());
                editor.remove("kit_" + kitNo + "_raw_" + i);
            } else if (selectedRawResIds[i] != 0) {
                editor.remove("kit_" + kitNo + "_uri_" + i);
                editor.putInt("kit_" + kitNo + "_raw_" + i, selectedRawResIds[i]);
            } else {
                editor.remove("kit_" + kitNo + "_uri_" + i);
                editor.remove("kit_" + kitNo + "_raw_" + i);
            }
        }

        if (assistSoundUri != null) {
            editor.putString("kit_" + kitNo + "_assist_uri", assistSoundUri.toString());
        } else {
            editor.remove("kit_" + kitNo + "_assist_uri");
        }
        editor.apply();
    }

    private void loadKitFromMemory(int kitNo) { 
        if (kitNo <= presetKitNames.length) {
            currentPresetKit = kitNo - 1;
            currentKitName = prefs.getString("kit_name_" + kitNo, presetKitNames[currentPresetKit]);
        } else {
            currentKitName = prefs.getString("kit_name_" + kitNo, "KIT " + kitNo);
        }
        txtKitName.setText(currentKitName);  

        for (int i = 0; i < PAD_COUNT; i++) {
            padVolume[i] = prefs.getFloat("kit_" + kitNo + "_vol_" + i, 0.80f);
            padPitch[i] = prefs.getFloat("kit_" + kitNo + "_pitch_" + i, 1.0f);
            padDelayOn[i] = prefs.getBoolean("kit_" + kitNo + "_dlyon_" + i, false);
            padDelayTime[i] = prefs.getFloat("kit_" + kitNo + "_dlyt_" + i, 150.0f);
            padDelayLevel[i] = prefs.getFloat("kit_" + kitNo + "_dlyl_" + i, 0.5f);
            padEqHigh[i] = prefs.getFloat("kit_" + kitNo + "_eqh_" + i, 0.0f);
            padEqMid[i] = prefs.getFloat("kit_" + kitNo + "_eqm_" + i, 0.0f);
            padEqLow[i] = prefs.getFloat("kit_" + kitNo + "_eql_" + i, 0.0f);
            padChokeGroup[i] = prefs.getInt("kit_" + kitNo + "_choke_" + i, 0);

            String uriStr = prefs.getString("kit_" + kitNo + "_uri_" + i, null);
            int rawResId = prefs.getInt("kit_" + kitNo + "_raw_" + i, 0);
            if (uriStr != null) {
                selectedWavUris[i] = Uri.parse(uriStr);
                selectedRawResIds[i] = 0;
                samples[i] = audioEngine.loadWavFromUri(i, selectedWavUris[i]);

                if (samples[i] != null) {
                    audioEngine.preloadSample(samples[i]);
                }
            } else if (rawResId != 0) {
                selectedWavUris[i] = null;
                selectedRawResIds[i] = rawResId;
                samples[i] = audioEngine.loadRawSound(i, rawResId);

                if (samples[i] != null) {
                    audioEngine.preloadSample(samples[i]);
                }
            } else {
                selectedWavUris[i] = null;
                if (kitNo <= presetKitNames.length) {
                    selectedRawResIds[i] = presetKits[currentPresetKit][i];
                } else {
                    selectedRawResIds[i] = presetKits[0][i];
                }
                samples[i] = audioEngine.loadRawSound(i, selectedRawResIds[i]);

                if (samples[i] != null) {
                    audioEngine.preloadSample(samples[i]);
                }
            }
        }

        String assistUriStr = prefs.getString("kit_" + kitNo + "_assist_uri", null);
        if (assistUriStr != null) {
            assistSoundUri = Uri.parse(assistUriStr);
            // Optional: loading the actual sound for the assist if there's a mechanism.
        } else {
            assistSoundUri = null;
        }

        seekVolume.setProgress((int) (padVolume[selectedPad] * 100));
        seekPitch.setProgress((int) ((padPitch[selectedPad] - 0.5f) * 100));
    }

    private void loadKitFromFolder(Uri folderUri) {
        try {
            DocumentFile kitFolder = DocumentFile.fromTreeUri(this, folderUri);
            if (kitFolder == null) {
                Toast.makeText(this, "Folder not found!", Toast.LENGTH_SHORT).show();
                return;
            }

            for (int i = 0; i < PAD_COUNT; i++) {
                // आपके मूल KitManager.DEFAULT_WAV_NAMES का सटीक उपयोग वापस डाल दिया है
                DocumentFile wav = kitFolder.findFile(KitManager.DEFAULT_WAV_NAMES[i]);
                if (wav != null) {
                    selectedWavUris[i] = wav.getUri();
                    selectedRawResIds[i] = 0;
                    samples[i] = audioEngine.loadWavFromUri(i, wav.getUri());

                    if (samples[i] != null) {
                        audioEngine.preloadSample(samples[i]);
                    }
                } else {
                    // Fallback to raw preset kit sound when folder file is missing
                    selectedWavUris[i] = null;
                    selectedRawResIds[i] = presetKits[currentPresetKit][i];
                    samples[i] = audioEngine.loadRawSound(i, selectedRawResIds[i]);
                    if (samples[i] != null) {
                        audioEngine.preloadSample(samples[i]);
                    }
                }
            }

            String folderName = kitFolder.getName();
            if (folderName != null) {
                currentKitName = folderName.replace(".mcn", "");
                txtKitName.setText(currentKitName);
            } 

            DocumentFile dataFile = kitFolder.findFile("kit_data.json");
            if (dataFile != null) {
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(dataFile.getUri());
                    if (is != null) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        is.close();
                        org.json.JSONObject jsonData = new org.json.JSONObject(sb.toString());
                        org.json.JSONArray volArray = jsonData.optJSONArray("volume");
                        org.json.JSONArray pitchArray = jsonData.optJSONArray("pitch");
                        org.json.JSONArray dlyOnArray = jsonData.optJSONArray("delayOn");
                        org.json.JSONArray dlyTArray = jsonData.optJSONArray("delayTime");
                        org.json.JSONArray dlyLArray = jsonData.optJSONArray("delayLevel");
                        org.json.JSONArray eqHArray = jsonData.optJSONArray("eqHigh");
                        org.json.JSONArray eqMArray = jsonData.optJSONArray("eqMid");
                        org.json.JSONArray eqLArray = jsonData.optJSONArray("eqLow");
                        org.json.JSONArray chokeArray = jsonData.optJSONArray("chokeGroup");
                        for (int i = 0; i < PAD_COUNT; i++) {
                            if (volArray != null) padVolume[i] = (float) volArray.getDouble(i);
                            if (pitchArray != null) padPitch[i] = (float) pitchArray.getDouble(i);
                            if (dlyOnArray != null) padDelayOn[i] = dlyOnArray.getBoolean(i);
                            if (dlyTArray != null) padDelayTime[i] = (float) dlyTArray.getDouble(i);
                            if (dlyLArray != null) padDelayLevel[i] = (float) dlyLArray.getDouble(i);
                            if (eqHArray != null) padEqHigh[i] = (float) eqHArray.getDouble(i);
                            if (eqMArray != null) padEqMid[i] = (float) eqMArray.getDouble(i);
                            if (eqLArray != null) padEqLow[i] = (float) eqLArray.getDouble(i);
                            if (chokeArray != null) padChokeGroup[i] = chokeArray.getInt(i);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            seekVolume.setProgress((int) (padVolume[selectedPad] * 100));
            seekPitch.setProgress((int) ((padPitch[selectedPad] - 0.5f) * 100));

            saveKitToMemory(kitIndex);
            Toast.makeText(this, "Kit Loaded Successfully!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Load Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveKitToFolder(Uri folderUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(this, folderUri);
            if (root == null) {
                Toast.makeText(this, "Folder access error!", Toast.LENGTH_SHORT).show();
                return;
            }

            DocumentFile kitFolder = root.findFile(currentKitName + ".mcn");
            if (kitFolder == null) {
                kitFolder = root.createDirectory(currentKitName + ".mcn");
            }

            if (kitFolder == null) {
                Toast.makeText(this, "Cannot create kit folder!", Toast.LENGTH_SHORT).show();
                return;
            }

            for (int i = 0; i < PAD_COUNT; i++) {
                if (selectedWavUris[i] != null || selectedRawResIds[i] != 0) {
                    // आपके मूल KitManager.DEFAULT_WAV_NAMES का सटीक उपयोग
                    DocumentFile old = kitFolder.findFile(KitManager.DEFAULT_WAV_NAMES[i]);
                    if (old != null) old.delete();

                    DocumentFile dest = kitFolder.createFile("audio/wav", KitManager.DEFAULT_WAV_NAMES[i]);
                    if (dest != null) {
                        if (selectedWavUris[i] != null) {
                            FileUtil.copyUriToUri(this, selectedWavUris[i], dest.getUri());
                        } else if (selectedRawResIds[i] != 0) {
                            FileUtil.copyRawToUri(this, selectedRawResIds[i], dest.getUri());
                        }
                    }
                }
            }

            DocumentFile dataFile = kitFolder.findFile("kit_data.json");
            if (dataFile != null) dataFile.delete();
            dataFile = kitFolder.createFile("application/json", "kit_data.json");
            if (dataFile != null) {
                try {
                    org.json.JSONObject jsonData = new org.json.JSONObject();
                    org.json.JSONArray volArray = new org.json.JSONArray();
                    org.json.JSONArray pitchArray = new org.json.JSONArray();
                    org.json.JSONArray dlyOnArray = new org.json.JSONArray();
                    org.json.JSONArray dlyTArray = new org.json.JSONArray();
                    org.json.JSONArray dlyLArray = new org.json.JSONArray();
                    org.json.JSONArray eqHArray = new org.json.JSONArray();
                    org.json.JSONArray eqMArray = new org.json.JSONArray();
                    org.json.JSONArray eqLArray = new org.json.JSONArray();
                    org.json.JSONArray chokeArray = new org.json.JSONArray();
                    for (int i = 0; i < PAD_COUNT; i++) {
                        volArray.put((double)padVolume[i]);
                        pitchArray.put((double)padPitch[i]);
                        dlyOnArray.put(padDelayOn[i]);
                        dlyTArray.put((double)padDelayTime[i]);
                        dlyLArray.put((double)padDelayLevel[i]);
                        eqHArray.put((double)padEqHigh[i]);
                        eqMArray.put((double)padEqMid[i]);
                        eqLArray.put((double)padEqLow[i]);
                        chokeArray.put(padChokeGroup[i]);
                    }
                    jsonData.put("volume", volArray);
                    jsonData.put("pitch", pitchArray);
                    jsonData.put("delayOn", dlyOnArray);
                    jsonData.put("delayTime", dlyTArray);
                    jsonData.put("delayLevel", dlyLArray);
                    jsonData.put("eqHigh", eqHArray);
                    jsonData.put("eqMid", eqMArray);
                    jsonData.put("eqLow", eqLArray);
                    jsonData.put("chokeGroup", chokeArray);
                    java.io.OutputStream out = getContentResolver().openOutputStream(dataFile.getUri());
                    if (out != null) {
                        out.write(jsonData.toString().getBytes());
                        out.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            Toast.makeText(this, "Kit Saved: " + currentKitName, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Save Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openListFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_LIST_FOLDER);
    }

    private void scanForMcnFolders(DocumentFile folder, ArrayList<DocumentFile> kitFolders, ArrayList<String> kitNames) {
        for (DocumentFile file : folder.listFiles()) {
            if (file == null) continue;

            String name = file.getName();
            if (name == null) continue;

            if (file.isDirectory()) {
                if (name.toLowerCase().endsWith(".mcn")) {
                    kitFolders.add(file);
                    kitNames.add(name.substring(0, name.length() - 4));
                } else {
                    scanForMcnFolders(file, kitFolders, kitNames);
                }
            }
        }
    }

    private void showKitListDialog(final Uri folderUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(this, folderUri);

            if (root == null || !root.exists() || !root.isDirectory()) {
                Toast.makeText(this, "Invalid folder! Choose again.", Toast.LENGTH_SHORT).show();
                openListFolderPicker();
                return;
            }

            final ArrayList<DocumentFile> kitFolders = new ArrayList<>();
            final ArrayList<String> kitNames = new ArrayList<>();

            scanForMcnFolders(root, kitFolders, kitNames);

            if (kitNames.size() == 0) {
                Toast.makeText(this, "No .mcn kit folders found in this folder!", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] items = kitNames.toArray(new String[0]);

            new AlertDialog.Builder(this)
                .setTitle("Select Kit")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DocumentFile selectedKitFolder = kitFolders.get(which);
                        loadKitFromFolder(selectedKitFolder.getUri());
                        saveKitToMemory(kitIndex);
                    }
                })
                .setNeutralButton("Change Folder", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openListFolderPicker();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "List Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveKitToMemory(kitIndex);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveKitToMemory(kitIndex);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveKitToMemory(kitIndex);
        closeMidiDevice();

        try {
            if (audioEngine != null) {
                audioEngine.stop();
                audioEngine = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

