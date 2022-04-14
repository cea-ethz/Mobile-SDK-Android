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
            JSONArray jsonEvents = obj.getJSONArray("events");
            System.out.println("Mission : " + missionName + " has " + jsonEvents.length() + " events");

            for (int i = 0; i < jsonEvents.length(); i++) {
                JSONObject eventObj = jsonEvents.getJSONObject(i);
                String eventType = eventObj.getString("type");
                float data0 = (float)eventObj.getDouble("data0");
                float data1 = (float)eventObj.getDouble("data1");
                LocalMissionEvent event = new LocalMissionEvent(LocalMissionEventType.valueOf(eventType),data0,data1);
                events.add(event);
            }
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

    @Override
    public String toString() {
        String out = "";
        for (LocalMissionEvent event : events) {
            out += event.eventType;
            out += "\n";
        }
        return(out);
    }
}
