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
import com.dji.sdk.sample.demo.flightcontroller.VirtualStickView;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.Helper;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.utils.VideoFeedView;
import com.dji.sdk.sample.internal.view.PresentableView;

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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

public class LocalMissionView extends RelativeLayout
        implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, PresentableView {

    private LocalMission localMission;

    private Compass compass;

    private float heading_real;

    private float pitch;
    private float roll;
    private float yaw;
    private float throttle;

    private Point3f positionEstimated;

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
    private TextView textViewListenerPositionGPS;

    public LocalMissionView(Context context) {
        super(context);
        init(context);

        positionEstimated = new Point3f();
    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_local_mission, this, true);

        //initAllKeys();
        initUI();
    }

    private void initUI() {
        primaryCoverView = findViewById(R.id.primary_cover_view);
        primaryVideoFeed = (VideoFeedView) findViewById(R.id.primary_video_feed);
        primaryVideoFeed.setCoverView(primaryCoverView);

        textViewListenerHeading = (TextView) findViewById(R.id.text_heading);
        textViewListenerVelocity = (TextView) findViewById(R.id.text_velocity);
        textViewListenerPositionEstimated = (TextView) findViewById(R.id.text_position_estimated);
        textViewListenerPositionGPS = (TextView) findViewById(R.id.text_position_gps);
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
        // Camera
        sourceListener = new VideoFeeder.PhysicalSourceListener() {
            @Override
            public void onChange(VideoFeeder.VideoFeed videoFeed, PhysicalSource newPhysicalSource) {
            }
        };
        setVideoFeederListeners(true);

        // FlightController
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            FlightController flightController =
                    ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController();

            flightController.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                    // Compass
                    if (null != compass) {
                        heading_real = compass.getHeading();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textViewListenerHeading.setText(getContext().getString(R.string.listener_heading,heading_real));
                            }
                        });
                    }
                    // Velocity, Estimated Position (Basic), Position (GPS)
                    float vx = djiFlightControllerCurrentState.getVelocityX();
                    float vy = djiFlightControllerCurrentState.getVelocityY();
                    float vz = djiFlightControllerCurrentState.getVelocityZ();

                    Vector3f velocity = new Vector3f(vx,vy,vz);

                    Vector3f scaledVelocity = new Vector3f(velocity);
                    scaledVelocity.scale(controllerUpdateTime);

                    positionEstimated.add(scaledVelocity);

                    LocationCoordinate3D positionGPS = djiFlightControllerCurrentState.getAircraftLocation();
                    GPSSignalLevel signalLevel = djiFlightControllerCurrentState.getGPSSignalLevel();


                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textViewListenerVelocity.setText(getContext().getString(R.string.listener_velocity,vx,vy,vz));
                            textViewListenerPositionEstimated.setText(getContext().getString(R.string.listener_position,positionEstimated.x,positionEstimated.y));
                            textViewListenerPositionGPS.setText(getContext().getString(R.string.listener_gps,signalLevel, positionGPS.getLatitude(),positionGPS.getLongitude()));
                        }
                    });

                }
            });
            if (ModuleVerificationUtil.isCompassAvailable()) {
                compass = flightController.getCompass();
            }
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

    @Override
    public void onClick(View v) {
        // Set Control Modes for Virtual Stick Inputs
        // Settings are for absolute height control, directional horizontal control relative to the
        // world, and absolute angle direction
        FlightController flightController = ModuleVerificationUtil.getFlightController();
        flightController.setVerticalControlMode(VerticalControlMode.POSITION);
        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flightController.setYawControlMode(YawControlMode.ANGLE);
        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

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
                        .sendVirtualStickFlightControlData(new FlightControlData(pitch,
                                        roll,
                                        yaw,
                                        throttle),
                                new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {

                                    }
                                });
            }
        }
    }
}
