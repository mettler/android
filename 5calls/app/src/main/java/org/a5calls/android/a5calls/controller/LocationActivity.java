package org.a5calls.android.a5calls.controller;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;

import org.a5calls.android.a5calls.FiveCallsApplication;
import org.a5calls.android.a5calls.R;
import org.a5calls.android.a5calls.model.AccountManager;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;

public class LocationActivity extends AppCompatActivity {
    private static final String TAG = "LocationActivity";

    // Allows parent activity to control the home button
    public static final String ALLOW_HOME_UP_KEY = "allowHomeUp";
    private static final int LOCATION_PERMISSION_REQUEST = 1;

    private final AccountManager accountManager = AccountManager.Instance;

    /**
     * 60647 is split district
     * 3240 W. Fullerton Ave Chicago IL 60647 should resolve to Gutierrez
     * 2076 N Hoyne Ave Chicago IL 60647-4559 is a Quigley
     */

    private boolean allowsHomeUp = false;
    private LocationListener mLocationListener;

    @BindView(R.id.zip_code) EditText zipEdit;
    @BindView(R.id.zip_code_submit) Button zipButton;
    @BindView(R.id.btn_gps) Button gpsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location);
        ButterKnife.bind(this);

        // Allow home up if required
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.getBooleanExtra(ALLOW_HOME_UP_KEY, false)) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                allowsHomeUp = true;
            }
        }

        // Load the zip code the user last used, if any.
        String zip = accountManager.getZip(this);
        if (!TextUtils.isEmpty(zip)) {
            zipEdit.setText(zip);
        }

        // Set listeners
        zipEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onSubmitZip(zipEdit.getText().toString());
                    return true;
                }
                return false;
            }
        });

        zipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSubmitZip(zipEdit.getText().toString());
            }
        });

        gpsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryGettingLocation();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (accountManager.allowAnalytics(this)) {
            // Obtain the shared Tracker instance.
            FiveCallsApplication application = (FiveCallsApplication) getApplication();
            Tracker tracker = application.getDefaultTracker();
            tracker.setScreenName(TAG);
            tracker.send(new HitBuilders.ScreenViewBuilder().build());
        }
    }

    @Override
    protected void onPause() {
        if (mLocationListener != null) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationManager.removeUpdates(mLocationListener);
        }
        super.onPause();
    }

    private void returnToMain() {
        // Make sure we're still alive
        if (isFinishing() || isDestroyed()) {
            return;
        }

        // If we came from MainActivity and return with another Intent, it will create a deep stack
        // of activities!
        if (allowsHomeUp) {
            onBackPressed();
        } else {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            // Try getting the location in a Runnable because it is possible that if the location
            // is cached we get it so fast that we returnToMain before we are done resuming, which
            // causes a crash.
            gpsButton.post(new Runnable() {
                @Override
                public void run() {
                    tryGettingLocation();
                }
            });
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * @return the last know best location
     */
    private void tryGettingLocation() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            }
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        String provider = locationManager.getBestProvider(new Criteria(), false);
        Location location = locationManager.getLastKnownLocation(provider);

        if (location == null) {
            mLocationListener = new LocationListener() {

                @Override
                public void onLocationChanged(Location location) {
                    onReceiveLocation(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {

                }

                @Override
                public void onProviderEnabled(String provider) {

                }

                @Override
                public void onProviderDisabled(String provider) {

                }
            };
            locationManager.requestLocationUpdates(provider, 10, 0, mLocationListener);

        } else {
            onReceiveLocation(location);
        }
    }

    private void onSubmitZip(String zip) {
        // Is it a string that is exactly 5 characters long?
        if (TextUtils.isEmpty(zip) || zip.length() != 5) {
            zipEdit.setError(getResources().getString(R.string.zip_error));
            return;
        }
        try {
            // Make sure it is a number, too, by trying to parse it.
            Integer.parseInt(zip);
        } catch (NumberFormatException e) {
            zipEdit.setError(getResources().getString(R.string.zip_error));
            return;
        }
        // If we made it here, the zip is valid! Update the UI and send the request.
        accountManager.setZip(this, zip);

        // Delete latlng, because the user specifically requested a zip and we default to lat/long.
        accountManager.setLat(this, null);
        accountManager.setLng(this, null);

        returnToMain();
    }


    private void onReceiveLocation(Location location) {
        accountManager.setLat(this, String.valueOf(location.getLatitude()));
        accountManager.setLng(this, String.valueOf(location.getLongitude()));

        // Update the zip field from the location
        if (location != null) {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            try {
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && addresses.size() > 0) {
                    accountManager.setZip(this, addresses.get(0).getPostalCode());
                }
            } catch (IOException e) {
                // Do nothing
            }
        }

        returnToMain();
    }
}
