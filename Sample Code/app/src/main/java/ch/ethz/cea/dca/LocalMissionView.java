package ch.ethz.cea.dca;

import android.app.Service;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.view.PresentableView;

import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.sdk.flightcontroller.FlightController;

public class LocalMissionView extends RelativeLayout
        implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, PresentableView {

    public LocalMissionView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        // Set Control Modes for Virtual Stick Inputs
        // Settings are for absolute height control, directional horizontal control relative to the
        // world, and absolute angle direction
        FlightController flightController = ModuleVerificationUtil.getFlightController();
        flightController.setVerticalControlMode(VerticalControlMode.POSITION);
        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flightController.setYawControlMode(YawControlMode.ANGLE);
        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);

        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_virtual_stick, this, true);

        //initAllKeys();
        //initUI();
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

    }

    @Override
    public int getDescription() {
        return 0;
    }

    @NonNull
    @Override
    public String getHint() {
        return null;
    }
}
