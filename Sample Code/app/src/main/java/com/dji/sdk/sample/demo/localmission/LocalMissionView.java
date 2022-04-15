package com.dji.sdk.sample.demo.localmission;

import android.app.Service;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.DialogUtils;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.utils.VideoFeedView;
import com.dji.sdk.sample.internal.view.PresentableView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import ch.ethz.cea.dca.*;

import dji.common.airlink.PhysicalSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.virtualstick.*;
import dji.common.util.CommonCallbacks;

import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.Compass;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.media.MediaFile;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

import static com.google.android.gms.internal.zzahn.runOnUiThread;

import javax.vecmath.Point2f;
import javax.vecmath.Vector2f;

public class LocalMissionView extends RelativeLayout
        implements View.OnClickListener, PresentableView {

    private LocalMission localMission;

    private Compass compass;

    private float heading_real;

    private float vstickPitch;
    private float vstickRoll;
    private float vstickYaw;
    private float vstickThrottle;

    private Point2f positionEstimated;

    private final float controllerUpdateTime = 0.1f;

    private Camera camera;

    private VideoFeedView primaryVideoFeed;
    private VideoFeeder.PhysicalSourceListener sourceListener;
    private View primaryCoverView;

    private Timer sendVirtualStickDataTimer;
    private SendVirtualStickDataTask sendVirtualStickDataTask;

    // UI Objects

    private TextView textViewListenerHeading;
    private TextView textViewListenerVelocity;
    private TextView textViewListenerPositionEstimated;
    private TextView textViewListenerVStick;
    private TextView textViewListenerPositionGPS;

    private TextView textViewMissionList;

    public LocalMissionView(Context context) {
        super(context);
        init(context);

        positionEstimated = new Point2f();
    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_local_mission, this, true);

        initUI();
    }

    private void initUI() {
        // Camera
        primaryCoverView = findViewById(R.id.primary_cover_view);
        primaryVideoFeed = (VideoFeedView) findViewById(R.id.primary_video_feed);
        primaryVideoFeed.setCoverView(primaryCoverView);

        // Flight info text displays
        textViewListenerHeading = (TextView) findViewById(R.id.text_heading);
        textViewListenerVelocity = (TextView) findViewById(R.id.text_velocity);
        textViewListenerPositionEstimated = (TextView) findViewById(R.id.text_position_estimated);
        textViewListenerVStick = (TextView) findViewById(R.id.text_vstick);
        textViewListenerPositionGPS = (TextView) findViewById(R.id.text_position_gps);
        textViewMissionList = (TextView) findViewById(R.id.lm_list_text);

        // Buttons
        findViewById(R.id.btn_mission_load).setOnClickListener(this);
        findViewById(R.id.btn_enable_virtual_stick).setOnClickListener(this);
        findViewById(R.id.btn_disable_virtual_stick).setOnClickListener(this);
        findViewById(R.id.btn_take_off).setOnClickListener(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();

        if (ModuleVerificationUtil.isCameraModuleAvailable()) {
            camera = DJISampleApplication.getAircraftInstance().getCamera();
            if (ModuleVerificationUtil.isMatrice300RTK() || ModuleVerificationUtil.isMavicAir2()) {
                camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError -> ToastUtils.setResultToToast("setFlatMode to PHOTO_SINGLE"));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> ToastUtils.setResultToToast("setMode to shoot_PHOTO"));
            }
            camera.setMediaFileCallback(new MediaFile.Callback() {
                @Override
                public void onNewFile(@NonNull MediaFile mediaFile) {
                    ToastUtils.setResultToToast("New photo generated");
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (null != sendVirtualStickDataTimer) {
            if (sendVirtualStickDataTask != null) {
                sendVirtualStickDataTask.cancel();

            }
            sendVirtualStickDataTimer.cancel();
            sendVirtualStickDataTimer.purge();
            sendVirtualStickDataTimer = null;
            sendVirtualStickDataTask = null;
        }
        tearDownListeners();
        super.onDetachedFromWindow();
    }


    private void setUpListeners() {
        // Receive : Camera
        sourceListener = new VideoFeeder.PhysicalSourceListener() {
            @Override
            public void onChange(VideoFeeder.VideoFeed videoFeed, PhysicalSource newPhysicalSource) {
            }
        };
        setVideoFeederListeners(true);

        // Receive : FlightController
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            FlightController flightController =
                    ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController();

            flightController.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                    runMission();
                    updateFightInfo(djiFlightControllerCurrentState);
                }
            });
            if (ModuleVerificationUtil.isCompassAvailable()) {
                compass = flightController.getCompass();
            }
        }
        // Send : VStick
        if (null == sendVirtualStickDataTimer) {
            sendVirtualStickDataTask = new LocalMissionView.SendVirtualStickDataTask();
            sendVirtualStickDataTimer = new Timer();
            sendVirtualStickDataTimer.schedule(sendVirtualStickDataTask, 0, 100);
        }
    }

    private void tearDownListeners() {
        setVideoFeederListeners(false);

        if(ModuleVerificationUtil.isFlightControllerAvailable()) {
            ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController().setStateCallback(null);
        }
    }

    private void setVideoFeederListeners(boolean isOpen) {
        if (VideoFeeder.getInstance() == null) return;

        final BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (product != null) {
            VideoFeeder.VideoDataListener primaryVideoDataListener =
                    primaryVideoFeed.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);
            if (isOpen) {
                VideoFeeder.getInstance().addPhysicalSourceListener(sourceListener);
            } else {
                VideoFeeder.getInstance().removePhysicalSourceListener(sourceListener);
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(primaryVideoDataListener);
            }
        }
    }

    private void updateFightInfo(FlightControllerState djiFlightControllerCurrentState) {
        // Read from Compass
        if (null != compass) {
            heading_real = compass.getHeading();
        }

        // Read from FC State about Velocity, Estimated Position (Basic), Position (GPS)
        float vx = djiFlightControllerCurrentState.getVelocityX();
        float vy = djiFlightControllerCurrentState.getVelocityY();
        float vz = djiFlightControllerCurrentState.getVelocityZ();

        Vector2f velocity = new Vector2f(vx,vy);

        Vector2f scaledVelocity = new Vector2f(velocity);
        scaledVelocity.scale(controllerUpdateTime);

        positionEstimated.add(scaledVelocity);

        LocationCoordinate3D positionGPS = djiFlightControllerCurrentState.getAircraftLocation();
        GPSSignalLevel signalLevel = djiFlightControllerCurrentState.getGPSSignalLevel();

        // Update UI Elements
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textViewListenerVelocity.setText(getContext().getString(R.string.listener_velocity,vx,vy,vz));
                textViewListenerPositionEstimated.setText(getContext().getString(R.string.listener_position,positionEstimated.x,positionEstimated.y));
                textViewListenerPositionGPS.setText(getContext().getString(R.string.listener_gps,signalLevel, positionGPS.getLatitude(),positionGPS.getLongitude()));
                textViewListenerHeading.setText(getContext().getString(R.string.listener_heading,heading_real));
                textViewListenerVStick.setText(getContext().getString(R.string.listener_vstick,vstickPitch,vstickRoll,vstickYaw,vstickThrottle));
            }
        });
    }

    private void runMission() {
        if (localMission == null || localMission.missionState != LocalMissionState.RUNNING) {
            return;
        }
        LocalMissionEvent lmEvent = localMission.getCurrentEvent();
        switch(lmEvent.eventType) {
            case GO_TO:
                Vector2f vec = new Vector2f(lmEvent.data0,lmEvent.data1);
                vec.sub(positionEstimated);
                float d = vec.length();
                if (d < 0.5) {
                    localMission.advance();
                }
                vec.normalize();
                vec.scale(0.1f);
                vstickPitch = vec.x;
                vstickRoll = vec.y;
                break;
            case AIM_AT:
                break;
            case PHOTO:
                break;
            case ALTITUDE:
                break;
            case ALIGN:
                break;
            default:
                System.out.println("Unhandled event type : " + lmEvent.eventType);
                break;
        }
    }

    @Override
    public void onClick(View v) {
        // Set Control Modes for Virtual Stick Inputs
        // Settings are for absolute height control, directional horizontal control relative to the
        // world, and absolute angle direction
        FlightController flightController = ModuleVerificationUtil.getFlightController();
        if (flightController == null) {
            return;
        }

        switch(v.getId()) {
            case R.id.btn_enable_virtual_stick:
                flightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        flightController.setVerticalControlMode(VerticalControlMode.POSITION);
                        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                        flightController.setYawControlMode(YawControlMode.ANGLE);
                        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);
                        // Advanced mode allows for GPS-stabilized hovering, and collision avoidance
                        // if desired
                        flightController.setVirtualStickAdvancedModeEnabled(true);
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;

            case R.id.btn_disable_virtual_stick:
                flightController.setVirtualStickModeEnabled(false, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;
            case R.id.btn_take_off:
                flightController.startTakeoff(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;
            case R.id.btn_mission_load:
                Thread thread = new Thread(new Runnable() {
                    @Override public void run() {
                        loadMission();
                    }
                });
                thread.start();
                break;
            case R.id.btn_mission_start:
                break;
        }
    }


    private void loadMission() {
        //TODO: Make this not hardcoded
        String urlString = "http://192.168.0.164:8000/mission.json";

        try {
            HttpURLConnection urlConnection = null;
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setReadTimeout(10000 /* milliseconds */ );
            urlConnection.setConnectTimeout(15000 /* milliseconds */ );
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
            br.close();

            String jsonString = sb.toString();

            localMission = new LocalMission();
            localMission.loadFromJson(jsonString);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textViewMissionList.setText(localMission.toString());
                }
            });

        }
        catch(Exception e) {
            System.out.println(e);
        }
    }


    @Override
    public int getDescription() {
        return R.string.flight_controller_listview_local_mission;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (ModuleVerificationUtil.isFlightControllerAvailable()) {
                DJISampleApplication.getAircraftInstance()
                        .getFlightController()
                        .sendVirtualStickFlightControlData(new FlightControlData(vstickPitch,
                                        vstickRoll,
                                        vstickYaw,
                                        vstickThrottle),
                                new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {

                                    }
                                });
            }
        }
    }
}
