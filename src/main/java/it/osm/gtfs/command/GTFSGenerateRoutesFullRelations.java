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

import com.google.common.collect.Multimap;
import it.osm.gtfs.input.GTFSParser;
import it.osm.gtfs.input.OSMParser;
import it.osm.gtfs.model.*;
import it.osm.gtfs.output.OSMRelationImportGenerator;
import it.osm.gtfs.utils.DownloadUtils;
import it.osm.gtfs.utils.GTFSImportSettings;
import it.osm.gtfs.utils.GTFSOSMWaysMatch;
import it.osm.gtfs.utils.StopsUtils;
import org.apache.commons.io.FileUtils;
import org.fusesource.jansi.Ansi;
import org.xml.sax.SAXException;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

import static org.fusesource.jansi.Ansi.ansi;


@CommandLine.Command(name = "fullrels", description = "Generate full relations including ways and stops (very long!)")
public class GTFSGenerateRoutesFullRelations implements Callable<Void> {

    @CommandLine.Option(names = {"-n", "--nowaymatching"}, description = "Generate stops-only relations (skips OSM ways matching)")
    Boolean noOsmWayMatching = false;

    @CommandLine.Option(names = {"-s", "--skipdownload"}, description = "Skip download of updated OSM ways")
    Boolean skipOsmWaysUpdate = false;

    @CommandLine.Option(names = {"-c", "--checkeverything"}, description = "Check stops with the operator tag value different than what is specified in the properties file")
    Boolean checkStopsOfAnyOperatorTagValue = false;

    @Override
    public Void call() throws IOException, ParserConfigurationException, SAXException {

        Map<String, OSMStop> gtfsIdOsmStopMap = StopsUtils.getGTFSIdOSMStopMap(OSMParser.readOSMStops(GTFSImportSettings.OSM_STOPS_FILE_PATH, checkStopsOfAnyOperatorTagValue));

        Map<String, Route> routes = GTFSParser.readRoutes(GTFSImportSettings.getInstance().getGTFSPath() +  GTFSImportSettings.GTFS_ROUTES_FILE_NAME);
        Map<String, Shape> shapes = GTFSParser.readShapes(GTFSImportSettings.getInstance().getGTFSPath() + GTFSImportSettings.GTFS_SHAPES_FILE_NAME);

        Map<String, StopsList> stopTimes = GTFSParser.readStopTimes(GTFSImportSettings.getInstance().getGTFSPath() +  GTFSImportSettings.GTFS_STOP_TIME_FILE_NAME, gtfsIdOsmStopMap);
        List<Trip> trips = GTFSParser.readTrips(GTFSImportSettings.getInstance().getGTFSPath() +  GTFSImportSettings.GTFS_TRIPS_FILE_NAME,
                routes, stopTimes);

        BoundingBox bb = new BoundingBox(gtfsIdOsmStopMap.values());

        //sorting set
        Multimap<String, Trip> groupedTrips = GTFSParser.groupTrip(trips, routes, stopTimes);
        Set<String> keys = new TreeSet<>(groupedTrips.keySet());



        
        //download of updated OSM ways in the GTFS bounding box
        if(!skipOsmWaysUpdate) {

            //delete old graphhopper cache TODO: i think this path won't be the default for when people will be using this tool from the jar file
            FileUtils.deleteDirectory(new File("graph-cache/"));

            String urlhighways = GTFSImportSettings.OSM_OVERPASS_API_SERVER + "data=[bbox];(way[\"highway\"~\"motorway|trunk|primary|tertiary|secondary|unclassified|motorway_link|trunk_link|primary_link|track|path|residential|service|secondary_link|tertiary_link|bus_guideway|road|busway\"];>;);out body;&bbox=" + bb.getAPIQuery();
            File fileOverpassHighways = new File(GTFSImportSettings.OSM_OVERPASS_WAYS_FILE_PATH);
            urlhighways = urlhighways.replace(" ", "%20"); //we substitute spaced with the uri code as httpurlconnection doesn't do that automatically, and it makes the request fail
            DownloadUtils.download(urlhighways, fileOverpassHighways);
        }


        new File(GTFSImportSettings.getInstance().getOutputPath() + "fullrelations").mkdirs();

        int id = 10000;
        for (String k:keys){
            Collection<Trip> allTrips = groupedTrips.get(k);
            Set<Trip> uniqueTrips = new HashSet<>(allTrips);

            for (Trip trip : uniqueTrips){

                int count = Collections.frequency(allTrips, trip);

                Route route = routes.get(trip.getRoute().getId());
                StopsList stops = stopTimes.get(trip.getTripId());
                List<Integer> osmWayIds = null;

                if(!noOsmWayMatching) {
                    System.out.println(ansi().fg(Ansi.Color.YELLOW).a("\nCreating full way-matched relation for trip " + trip.getName() + " tripID=" + trip.getTripId() +  " ...").reset());

                    Shape shape = shapes.get(trip.getShapeId());

                    String xmlGPXShape = shape.getGPXasSegment(route.getShortName());

                    //TODO: need to check if the way matches are ordered well
                    osmWayIds = new GTFSOSMWaysMatch().runMatch(xmlGPXShape);
                }else {
                    System.out.println(ansi().fg(Ansi.Color.YELLOW).a("Creating stops-only relation " + trip.getName() + " tripID=" + trip.getTripId() +  " ...").reset());
                }

                FileOutputStream f = new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + "fullrelations/r" + id + " " + route.getShortName().replace("/", "B") + " " + trip.getName().replace("/", "_") + "_" + count + ".osm");
                f.write(OSMRelationImportGenerator.getRelation(bb, stops, osmWayIds, trip, route).getBytes());
                f.close();
                f = new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + "fullrelations/r" + id++ + " " + route.getShortName().replace("/", "B") + " " + trip.getName().replace("/", "_") + "_" + count + ".txt");
                f.write(stops.getRelationAsStopList(trip, route).getBytes());
                f.close();
            }

        }

        System.out.println(ansi().fg(Ansi.Color.GREEN).a("\nRelations generation completed!").reset());

        if(!noOsmWayMatching) {
            System.out.println(ansi().fg(Ansi.Color.YELLOW).a("\nBe aware that the IDs of OSM's ways can change anytime!").reset());
            System.out.println(ansi().fg(Ansi.Color.YELLOW).a("This means you can encounter problems when uploading the relations to OSM in a different time window.").reset());
        }

        return null;
    }
}
