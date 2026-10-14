package com.rpgworldgen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A planned road between two points, as a waypoint polyline. */
public class PlannedRoad {
    private final String fromId;
    private final String toId;
    private final List<int[]> waypoints = new ArrayList<>();

    public PlannedRoad(String fromId, String toId) {
        this.fromId = fromId;
        this.toId = toId;
    }

    public void addWaypoint(int x, int z) { waypoints.add(new int[]{x, z}); }

    public String getFromId() { return fromId; }
    public String getToId() { return toId; }
    public List<int[]> getWaypoints() { return Collections.unmodifiableList(waypoints); }
}
