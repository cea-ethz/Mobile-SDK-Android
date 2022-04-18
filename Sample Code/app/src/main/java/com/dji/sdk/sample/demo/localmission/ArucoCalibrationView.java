package com.dji.sdk.sample.demo.localmission;

import android.app.Service;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.utils.VideoFeedView;
import com.dji.sdk.sample.internal.view.PresentableView;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import ch.ethz.cea.dca.CameraCalibrator;
import dji.common.airlink.PhysicalSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaFileInfo;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public class ArucoCalibrationView extends RelativeLayout
        implements
        View.OnClickListener,
        PresentableView,
        CameraBridgeViewBase.CvCameraViewListener2,
        CameraCalibrator.OnAddFrameListener {

    private Mat rgb;
    private CameraCalibrator calibrator;

    private Camera camera;
    private CameraBridgeViewBase cameraBridge;

    // Live in-app video feed
    private VideoFeedView primaryVideoFeed;
    private VideoFeeder.PhysicalSourceListener sourceListener;
    private View primaryCoverView;

    public ArucoCalibrationView(Context context) {
        super(context);
        init(context);

        //cameraBridge = findViewById(R.id.primary_video_feed);
    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_aruco_calibration, this, true);

        initUI();
    }

    private void initUI() {
        primaryCoverView = findViewById(R.id.primary_cover_view);
        primaryVideoFeed = (VideoFeedView) findViewById(R.id.primary_video_feed);
        primaryVideoFeed.setCoverView(primaryCoverView);

        findViewById(R.id.btn_aruco_capture).setOnClickListener(this);
        findViewById(R.id.btn_aruco_calibrate).setOnClickListener(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();
    }


    @Override
    protected void onDetachedFromWindow() {
        tearDownListeners();
        super.onDetachedFromWindow();
    }

    private void setUpListeners() {
        // Receive : Camera

        if (ModuleVerificationUtil.isCameraModuleAvailable()) {
            camera = DJISampleApplication.getAircraftInstance().getCamera();
            if (ModuleVerificationUtil.isMatrice300RTK() || ModuleVerificationUtil.isMavicAir2()) {
                camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError -> ToastUtils.setResultToToast("setFlatMode to PHOTO_SINGLE"));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> ToastUtils.setResultToToast("setMode to shoot_PHOTO"));
            }

            camera.setNewGeneratedMediaFileInfoCallback(new MediaFile.NewFileInfoCallback() {
                @Override
                public void onNewFileInfo(@NonNull MediaFileInfo mediaFileInfo) {
                    ToastUtils.setResultToToast("New photo generated");
                }
            });
        }

        sourceListener = new VideoFeeder.PhysicalSourceListener() {
            @Override
            public void onChange(VideoFeeder.VideoFeed videoFeed, PhysicalSource newPhysicalSource) {
            }
        };
        setVideoFeederListeners(true);

        //Camera camera = DJISampleApplication.getProductInstance().getCamera();
        camera.setSystemStateCallback(new SystemState.Callback() {
            @Override
            public void onUpdate(@NonNull SystemState systemState) {
                if ((!systemState.isShootingSinglePhoto()) && (!systemState.isStoringPhoto())) {
                }
            }
        });
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

    private void tearDownListeners() {
        setVideoFeederListeners(false);
//        if(ModuleVerificationUtil.isFlightControllerAvailable()) {
//            ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController().setStateCallback(null);
//        }
    }


    @Override
    public int getDescription() {
        return R.string.aruco_calibration_view_name;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    @Override
    public void onClick(View v) {
        switch(v.getId()) {
            case R.id.btn_aruco_capture:
                calibrator.addFrame();
                break;

            case R.id.btn_aruco_calibrate:

                break;
        }
    }

    @Override
    public void onAddFrame(boolean added) {
        // Succes or failure feedback if sufficient tags are in frame.
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        calibrator = new CameraCalibrator(width, height);
        calibrator.setOnAddFrameListener(this);

        rgb = new Mat();
    }

    @Override
    public void onCameraViewStopped() {
        rgb.release();
        calibrator.release();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Imgproc.cvtColor(inputFrame.rgba(), rgb, Imgproc.COLOR_RGBA2RGB);

        calibrator.render(rgb, inputFrame.gray());

        return rgb;
    }


}
