package it.osm.gtfs.utils;

import it.osm.gtfs.model.GTFSStop;
import it.osm.gtfs.model.OSMStop;

/**
 * this class contains methods to automate things related to Stops
 */
public class StopsUtils {

    /***
     *
     *
     * @param gtfsStop The GTFS stop
     * @param osmStop The OSM stop
     * @return Returns whether the two stops are the same stop or not
     */
    public static boolean match(GTFSStop gtfsStop, OSMStop osmStop) {

        double distanceBetween = OSMDistanceUtils.distVincenty(gtfsStop.getLat(), gtfsStop.getLon(), osmStop.getLat(), osmStop.getLon());
        String debugData = "GTFS Stop data: [" + gtfsStop + "] -> OSM Stop data: [" + osmStop +  "], exact distance between: " + distanceBetween + " m";

        if (osmStop.getCode() != null && osmStop.getCode().equals(gtfsStop.getCode())) {

            if (distanceBetween < 15 || (osmStop.getGtfsId() != null && gtfsStop.getGtfsId() != null && osmStop.getGtfsId().equals(gtfsStop.getGtfsId()) && osmStop.isRevised())){
                //if the stops are less than 15m far away (with only the ref code in common) OR are already linked with gtfsid AND the OSM stop is already revised (if it has the tag that this tool creates during the import, because if the stop was already checked by a real person we know this is probably the real position of the stop. In other cases the stops can be gtfs-is-matched but the position could have been changed)
                return true;
            }else if (distanceBetween < 1000) {
                System.err.println("Warning: Stops with same ref-code tag with dist > 5 m (and less than 1km) / " + debugData);

                osmStop.setNeedsPositionReview(true); //the position of the osm stop needs to be reviewed as it most probably may have changed

                return true;
            }

        }else if (distanceBetween < 15 && osmStop.getGtfsId() != null && gtfsStop.getGtfsId() != null && osmStop.getGtfsId().equals(gtfsStop.getGtfsId())){
            //if the stops have different ref tag code, same gtfs_id and are less than 15m far away
            System.err.println("Warning: Two stops with different ref-code tag but equal gtfs_id matched / " + debugData);
            return true;
        }

        return false;
    }


}
