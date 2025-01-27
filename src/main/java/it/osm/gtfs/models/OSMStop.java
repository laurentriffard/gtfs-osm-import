package it.osm.gtfs.models;

import it.osm.gtfs.enums.OSMStopType;
import it.osm.gtfs.enums.WheelchairAccess;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Node;

public class OSMStop extends Stop {

    public GTFSStop gtfsStopMatchedWith;
    public Node originalXMLNode;
    private boolean needsPositionReview = false;
    private boolean isRevised = false;
    private boolean isDisused = false;


    public OSMStop(String gtfsId, String code, GeoPosition geoPosition, String name, String operator, OSMStopType stopType, WheelchairAccess wheelchairAccessibility) {
        super(gtfsId, code, geoPosition, name, operator, stopType, wheelchairAccessibility);
    }

    public String getOSMId() {
        return (originalXMLNode == null) ? null : originalXMLNode.getAttributes().getNamedItem("id").getNodeValue();
    }

    public void setNeedsPositionReview(boolean needsGuiReview) {
        this.needsPositionReview = needsGuiReview;
    }

    public void setIsRevised(boolean isRevised) {
        this.isRevised = isRevised;
    }


    public boolean isRevised() {
        return isRevised;
    }

    public boolean needsPositionReview() {
        return needsPositionReview;
    }


    @Override
    public String toString() {
        return "OSMStop [" +
                ((originalXMLNode != null) ? "osmid=" + getOSMId() : "osmid=?") +
                ", gtfsId=" +
                getGtfsId() + ", code=" + getCode() + ", lat=" + getGeoPosition().getLatitude() +
                ", lon=" +
                getGeoPosition().getLongitude() +
                ", name=" +
                getName() +
                ", operator=" +
                getOperator() +
                ", accessibility=" + getWheelchairAccessibility()
                + ", stopType=" + getStopType() +
                ", isRevised=" + isRevised() + "]";
    }

    public boolean isDisused() {
        return isDisused;
    }

    public void setDisused(boolean disused) {
        isDisused = disused;
    }
}
