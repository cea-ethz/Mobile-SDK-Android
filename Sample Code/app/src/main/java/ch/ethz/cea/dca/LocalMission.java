package ch.ethz.cea.dca;

import java.util.ArrayList;

import static ch.ethz.cea.dca.LocalMissionEventType.*;

import org.json.JSONArray;
import org.json.JSONObject;

public class LocalMission {

    public ArrayList<LocalMissionEvent> events;

    /**
     * Empty Mission
     */
    public LocalMission() {
        events = new ArrayList<LocalMissionEvent>();
    }

    public void loadFromJson(String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);
            String missionName = obj.getString("mission_name");
            JSONArray events = obj.getJSONArray("events");
            System.out.println("Mission : " + missionName + " has " + events.length() + " events");
        }
        catch(Exception e) {
            System.out.println(e);
        }


    }

    public void loadDemoMission() {
        events = new ArrayList<LocalMissionEvent>();
        events.add(new LocalMissionEvent(GO_TO,10,0));
        events.add(new LocalMissionEvent(GO_TO,10,10));
        events.add(new LocalMissionEvent(GO_TO,0,10));
        events.add(new LocalMissionEvent(GO_TO,0,0));
    }
}
