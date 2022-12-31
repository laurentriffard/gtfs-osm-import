/**
 Licensed under the GNU General Public License version 3
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.gnu.org/licenses/gpl-3.0.html

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 **/

package it.osm.gtfs.command;

import it.osm.gtfs.command.gui.GTFSStopsReviewGui;
import it.osm.gtfs.enums.WheelchairAccess;
import it.osm.gtfs.input.GTFSParser;
import it.osm.gtfs.input.OSMParser;
import it.osm.gtfs.model.BoundingBox;
import it.osm.gtfs.model.GTFSStop;
import it.osm.gtfs.model.OSMStop;
import it.osm.gtfs.output.OSMBusImportGenerator;
import it.osm.gtfs.utils.GTFSImportSettings;
import it.osm.gtfs.utils.OSMDistanceUtils;
import it.osm.gtfs.utils.OSMXMLUtils;
import it.osm.gtfs.utils.StopsUtils;
import org.fusesource.jansi.Ansi;
import org.jxmapviewer.viewer.GeoPosition;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.fusesource.jansi.Ansi.ansi;


@CommandLine.Command(name = "stops", description = "Generate files to import bus stops into osm merging with existing stops")
public class GTFSGenerateBusStopsImport implements Callable<Void> {

    @CommandLine.Option(names = {"-c", "--checkeverything"}, description = "Check stops with the operator tag value different than what is specified in the properties file")
    Boolean checkStopsOfAnyOperatorTagValue = false;

    @CommandLine.Option(names = {"-n", "--noreview"}, description = "disables GUI review, for every node that is too distant from the GTFS coords generates a new stop, and then it just generates the new change files.")
    Boolean noGuiReview = false;

    final ArrayList<OSMStop> osmStopsToReview = new ArrayList<>(); //TODO: maybe find a better place for these two variables?

    final Map<OSMStop, GeoPosition> finalGeopositions = new HashMap<>();


    @Override
    public Void call() throws IOException, ParserConfigurationException, SAXException, TransformerException {

        List<GTFSStop> gtfsStopsList = GTFSParser.readStops(GTFSImportSettings.getInstance().getGTFSPath() + GTFSImportSettings.GTFS_STOP_FILE_NAME);
        BoundingBox bb = new BoundingBox(gtfsStopsList);

        List<OSMStop> osmStopsList = OSMParser.readOSMStops(GTFSImportSettings.OSM_STOP_FILE_PATH, checkStopsOfAnyOperatorTagValue);


        //TODO: TO REMOVE THIS IS ONLY FOR A QUICK DEBUG!!!!
        //List<OSMStop> halfosm = osmStopsList.subList(0, 500);


        //first matching phase between GTFS and OSM stops - check the StopUtils match() function to understand the criteria used to consider whether the GTFS and OSM stops are the same or not
        for (GTFSStop gtfsStop : gtfsStopsList){
            for (OSMStop osmStop : osmStopsList){
                if (StopsUtils.match(gtfsStop, osmStop)) {
                    if (osmStop.isTramStop()) {

                        //we check for multiple matches for tram stops && bus stops, and we handle them based on how distant the current loop stop and the already matched stop are
                        if(gtfsStop.railwayStopMatchedWith != null) {
                            double distanceBetweenCurrentStop = OSMDistanceUtils.distVincenty(gtfsStop.getGeoPosition().getLatitude(), gtfsStop.getGeoPosition().getLongitude(), osmStop.getGeoPosition().getLatitude(), osmStop.getGeoPosition().getLongitude());
                            double distanceBetweenAlreadyMatchedStop = OSMDistanceUtils.distVincenty(gtfsStop.getGeoPosition().getLatitude(), gtfsStop.getGeoPosition().getLongitude(), gtfsStop.railwayStopMatchedWith.getGeoPosition().getLatitude(), gtfsStop.railwayStopMatchedWith.getGeoPosition().getLongitude());

                            if (distanceBetweenCurrentStop > distanceBetweenAlreadyMatchedStop){
                                continue;
                            }

                            gtfsStop.railwayStopMatchedWith.gtfsStopMatchedWith = null;

                        }

                        gtfsStop.railwayStopMatchedWith = osmStop;

                    } else {
                        if(osmStop.gtfsStopMatchedWith != null || gtfsStop.osmStopMatchedWith != null){
                            double distanceBetweenCurrentStop = OSMDistanceUtils.distVincenty(gtfsStop.getGeoPosition().getLatitude(), gtfsStop.getGeoPosition().getLongitude(), osmStop.getGeoPosition().getLatitude(), osmStop.getGeoPosition().getLongitude());
                            double distanceBetweenAlreadyMatchedStop = OSMDistanceUtils.distVincenty(gtfsStop.getGeoPosition().getLatitude(), gtfsStop.getGeoPosition().getLongitude(), gtfsStop.osmStopMatchedWith.getGeoPosition().getLatitude(), gtfsStop.osmStopMatchedWith.getGeoPosition().getLongitude());

                            //in case of multiple matching we check what stop is the closest one to the gtfs coordinates between the current loop stop and the already-matched stop
                            if (distanceBetweenCurrentStop > distanceBetweenAlreadyMatchedStop){

                                //in case the already-matched stop is the closest one to the gtfs coordinates then we skip setting the stop match variables, and we go ahead with the loop
                                continue;
                            }

                            //in case the current loop stop is the closest one to the gtfs coordinates, we remove the matched gtfs stop from the  already matched osm stop
                            gtfsStop.osmStopMatchedWith.gtfsStopMatchedWith = null;
                            //gtfsStop.osmStopMatchedWith = null; nope because we just replace 5 lines later
                        }

                        gtfsStop.stopsMatchedWith.add(osmStop);
                        osmStop.stopsMatchedWith.add(gtfsStop);

                        gtfsStop.osmStopMatchedWith = osmStop;

                    }

                    osmStop.gtfsStopMatchedWith = gtfsStop;

                }
            }
        }

        //second matching phase by checking all osm stops again (also checking stops that didn't get matched && those that we don't consider matched)
        {
            //TODO: check if other tags of the node are in line with GTFS data

            int matched_stops = 0;
            int not_matched_osm_stops = 0;
            int stopsToReview = 0;

            OSMBusImportGenerator bufferNotMatchedStops = new OSMBusImportGenerator(bb);
            OSMBusImportGenerator bufferMatchedStops = new OSMBusImportGenerator(bb);

            for (OSMStop osmStop : osmStopsList) {
                Element originalNode = (Element) osmStop.originalXMLNode;

                //we check if the osm stop got matched with a gtfs stop AND only IF the osm stop needs the position review but the user doesn't want to review the stops then we consider the stop as not matched and we handle it in the else case
                if (osmStop.gtfsStopMatchedWith != null && !(osmStop.needsPositionReview() && noGuiReview)){

                    //we update the XML data according to the relative GTFS stop data
                    StopsUtils.updateOSMNodeMetadata(osmStop);

                    //for stops that need a position review
                    if (osmStop.needsPositionReview()) {

                        osmStopsToReview.add(osmStop);
                        stopsToReview++;
                    } else { //we will add the stops that need review to the matched file after the review later

                        //add the node to the buffer of matched stops
                        bufferMatchedStops.appendNode(originalNode);
                    }

                    matched_stops++;

                } else {
                    String notMatchedStringOutput = "OSM Stop node id " + osmStop.getOSMId() + " (ref=" + osmStop.getCode() + ", gtfs_id=" + osmStop.getGtfsId() + ")" + " didn't get matched to a GTFS stop as either they are too distant or the ref code is no more available in gtfs.";

                    System.out.println(notMatchedStringOutput);

                    not_matched_osm_stops++;

                    //TODO: for not matched stops we should signal that the node should be removed in the XML, like action = delete or something like that
                    bufferNotMatchedStops.appendNode(originalNode);

                }
            }


            if (matched_stops > 0) {
                bufferMatchedStops.end();
                bufferMatchedStops.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_MATCHED_WITH_UPDATED_METADATA));


                System.out.println(ansi().fg(Ansi.Color.GREEN).a("Matched OSM stops with GTFS data with updated metadata applied (new gtfs ids, names etc.): ").reset().a(matched_stops).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to review data: " + GTFSImportSettings.OUTPUT_MATCHED_WITH_UPDATED_METADATA + ")").reset());

                if (noGuiReview) {
                    System.out.println(ansi().fg(Ansi.Color.CYAN).a("You chose to not review the stops that need manual position review. Therefore these stops will be considered to be removed and a new stop node will be created for each of those removed stops with the updated coordinates.").reset());
                } else {
                    System.out.println("(" + ansi().fg(Ansi.Color.CYAN).a("Stops that need manual position review: ").reset().a(stopsToReview) + ")");
                }
            } else {
                System.out.println(ansi().fg(Ansi.Color.YELLOW).a("No OSM stop got matched with GTFS data!").reset());
            }

            if (not_matched_osm_stops > 0){
                bufferNotMatchedStops.end();
                bufferNotMatchedStops.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_NOT_MATCHED_STOPS));
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("NOT MATCHED OSM stops that should be *removed* from OSM: ").reset().a(not_matched_osm_stops).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to review data: " + GTFSImportSettings.OUTPUT_NOT_MATCHED_STOPS + ")").reset());
            }
        }

        //stops position review with GUI
        //TODO: only execute if the noreview flag is set to false
        {
            if(!noGuiReview) {
                System.out.println("Starting stop positions review...");

                final Object lockObject = new Object();

                final GTFSStopsReviewGui reviewGui = new GTFSStopsReviewGui(osmStopsToReview, finalGeopositions, lockObject);

                synchronized (lockObject) { //i don't really know if this lock-sync thing is really needed to make the tool stay up when the gui is started
                    try {
                        lockObject.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println("Stop review done");
            }


        }

        //new stops from gtfs data
        {
            int new_stops_from_gtfs = 0;
            OSMBusImportGenerator buffer = new OSMBusImportGenerator(bb);

            for (GTFSStop gtfsStop:gtfsStopsList) {
                if (gtfsStop.osmStopMatchedWith == null && gtfsStop.stopsMatchedWith.size() == 0 || (gtfsStop.osmStopMatchedWith != null && gtfsStop.osmStopMatchedWith.needsPositionReview() && noGuiReview)){
                    new_stops_from_gtfs++;

                    //we create the new node with new tags here
                    buffer.appendNode(gtfsStop.getNewXMLNode(buffer));
                }
            }

            buffer.end();

            if (new_stops_from_gtfs > 0){
                buffer.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_NEW_STOPS_FROM_GTFS + ".osm"));
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("New stops from GTFS (unmatched stops from GTFS): ").reset().a(new_stops_from_gtfs).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to import data: " + GTFSImportSettings.OUTPUT_NEW_STOPS_FROM_GTFS + ".osm)").reset());
            } else {
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("New stops from GTFS (unmatched stops from GTFS): ").reset().a(new_stops_from_gtfs));
            }
        }


        return null;
    }
}
