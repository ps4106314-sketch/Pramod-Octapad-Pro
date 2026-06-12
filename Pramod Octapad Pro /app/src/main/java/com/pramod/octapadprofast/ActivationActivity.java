package com.pramod.octapadpromidi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class ActivationActivity extends Activity {

    private EditText editActivationKey;
    private Button btnActivate;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    private static final String PREF_NAME = "OctapadSettings";
    private static final String KEY_IS_ACTIVATED = "is_activated";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_IS_ACTIVATED, false)) {
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_activation);

        editActivationKey = findViewById(R.id.editActivationKey);
        btnActivate = findViewById(R.id.btnActivate);
        progressBar = findViewById(R.id.progressBar);

        btnActivate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activateApp();
            }
        });
    }

    private void activateApp() {
        final String key = editActivationKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter an activation key", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnActivate.setEnabled(false);

        final String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("activation_keys").child(key);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                progressBar.setVisibility(View.GONE);
                btnActivate.setEnabled(true);

                if (snapshot.exists()) {
                    String status = snapshot.child("status").getValue(String.class);
                    String dbDeviceId = snapshot.child("device_id").getValue(String.class);

                    if ("unused".equals(status)) {
                        // Mark as used and bind to this device
                        snapshot.getRef().child("status").setValue("used");
                        snapshot.getRef().child("device_id").setValue(deviceId);
                        successAndProceed();
                    } else if ("used".equals(status) && deviceId != null && deviceId.equals(dbDeviceId)) {
                        // Already used but on this exact same device
                        successAndProceed();
                    } else {
                        Toast.makeText(ActivationActivity.this, "Key is already used on another device!", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(ActivationActivity.this, "Invalid Activation Key!", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
                btnActivate.setEnabled(true);
                Toast.makeText(ActivationActivity.this, "Database Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void successAndProceed() {
        prefs.edit().putBoolean(KEY_IS_ACTIVATED, true).apply();
        Toast.makeText(this, "Activation Successful!", Toast.LENGTH_SHORT).show();
        startMainActivity();
    }

    private void startMainActivity() {
        Intent intent = new Intent(ActivationActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
