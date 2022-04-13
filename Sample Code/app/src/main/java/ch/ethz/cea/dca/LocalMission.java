package ch.ethz.cea.dca;

import java.util.ArrayList;

import static ch.ethz.cea.dca.LocalMissionEventType.*;

public class LocalMission {

    public ArrayList<LocalMissionEvent> events;

    /**
     * Empty Mission
     */
    public LocalMission() {
        events = new ArrayList<LocalMissionEvent>();
    }

    public void loadFromServer(String fileName) {

    }

    public void loadDemoMission() {
        events = new ArrayList<LocalMissionEvent>();
        events.add(new LocalMissionEvent(GO_TO,10,0));
        events.add(new LocalMissionEvent(GO_TO,10,10));
        events.add(new LocalMissionEvent(GO_TO,0,10));
        events.add(new LocalMissionEvent(GO_TO,0,0));
    }
}
