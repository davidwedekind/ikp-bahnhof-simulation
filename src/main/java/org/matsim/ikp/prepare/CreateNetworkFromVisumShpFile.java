package org.matsim.ikp.prepare;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.util.Assert;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.ReferenceIdentifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** @author: davidwedekind */

public class CreateNetworkFromVisumShpFile {

    /**
     * This class provides functionality to convert network shapefiles exported from Visum
     * to a matsim network.xml
     *
     * The method 'createNetwork' takes the node and link shape file paths and additionally information on which linkModes to set
     */

    private static final Logger log = Logger.getLogger(CreateNetworkFromVisumShpFile.class);

    // ToDo: It would be nice to have the settings in a custom config group
    // SETTINGS ...

    // general settings
    public static String COORD_REF_SYS = "epsg:32632";
    // node file settings
    public static String NODE_ID_ATTR = "NO";
    public static String NODE_LN_ATTR = "NAME";
    // link file settings
    public static String LINK_ID_ATTR = "NO";
    public static String FROM_NODE_ID_ATTR = "FROMNODENO";
    public static String TO_NODE_ID_ATTR = "TONODENO";
    public static String LENGTH_ATTR_1ST_CHOICE = "GEOM_LEN~8"; // osm length in m
    public static String LENGTH_ATTR_2ND_CHOICE = "LENGTH"; // visum length in km
    public static String WIDTH_ATTR = "GEOM_WIDTH";
    public static String MAX_SPEED_ATTR = "V0_PRTSY~1";
    public static String LINK_TYPE_ATTR = "LINKTYPE~4";
    public static String OSM_ID_ATTR = "OSM_ID";
    public static String OSM_NAME_ATTR = "OSM_NAME";

    // Matsim internally reserves space for a car of 7.5 * 3.75 metres
    // According to RIL813 in peak hours 1 P can fit into 1 m^2
    public static double CAR_SPACE = (7.5 * 3.75);
    public static double PLATFORM_CLEARANCE_TIME = 150.; // in s
    public static double stdWidth = 3.;
    public static double minCapacity = 20.;
    public static double maxCapacity = 999.;
    public static List<String> typeNotRelevant = List.of("gesperrt", "Fussweg_Verbinder_Aufzug");




    public static void main(String[] args) {
        CreateNetworkFromVisumShpFile.Input input = new CreateNetworkFromVisumShpFile.Input();
        JCommander.newBuilder().addObject(input).build().parse(args);
        String ndShpFile = input.ndShpFile;
        String lnkShpFile = input.lnkShpFile;
        String netOutput = input.netOutput;
        log.info("Input shapefile with nodes: " + ndShpFile);
        log.info("Input shapefile with links: " + lnkShpFile);
        log.info("Output network file path: " + netOutput);

        Set<String> linkModes = Set.of("walk_1_39");
        Network net = createNetwork(Path.of(ndShpFile), Path.of(lnkShpFile), linkModes);
        writeNetwork(net, Path.of(netOutput));
    }

    public static Network createNetwork(Path nodeShpFile, Path linkShpFile, Set<String> linkModes){
        log.info("Start creating network");
        Network net = NetworkUtils.createNetwork();
        NetworkFactory fac = net.getFactory();
        net.getAttributes().putAttribute("coordinateReferenceSystem", COORD_REF_SYS);
        addNodes(net, nodeShpFile);
        addLinks(net, linkShpFile, linkModes);
        return net;
    }


    private static void addNodes(Network net, Path nodeShpFile){
        log.info("Add nodes");
        log.info(String.format("Read nodes from shapefile: %s", nodeShpFile));
        performCRSCheck(nodeShpFile);

        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(String.valueOf(nodeShpFile));
        String ptAssMessage = "The parsed shapefile contains geometries which are not of type 'point'.";
        // create one node per point geometry feature in shapefile
        for(var feature: features){
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            Assert.isTrue(geometry instanceof Point, ptAssMessage);
            Point pt = (Point) geometry;
            createNode(net,
                    feature.getAttribute(NODE_ID_ATTR).toString(),
                    feature.getAttribute(NODE_LN_ATTR).toString(),
                    pt.getX(),
                    pt.getY());
        }
    }


    public static void createNode(Network net, String nodeId, String longName, double x, double y){
        Node nd = net.getFactory().createNode(Id.createNodeId(nodeId), new Coord(x, y));
        nd.getAttributes().putAttribute("longName", longName);
        net.addNode(nd);
    }


    private static void addLinks(Network net, Path linkShpFile, Set<String> linkModes) {
        log.info("Add links");
        log.info(String.format("Read links from shapefile: %s", linkShpFile));
        performCRSCheck(linkShpFile);

        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(String.valueOf(linkShpFile));
        // Precondition: export directional vertices from Visum
        for(var feature: features) {
            // there are irrelevant links in shapefile
            if (! (typeNotRelevant.contains(feature.getAttribute(LINK_TYPE_ATTR).toString()))){

                // Differentiate id between directional links
                String linkId = feature.getAttribute(LINK_ID_ATTR).toString();
                if (net.getLinks().containsKey(Id.createLinkId(linkId + "_0"))){
                    linkId = linkId + "_1";
                } else {
                    linkId = linkId + "_0";
                }

                double length;
                if (feature.getAttribute(LENGTH_ATTR_1ST_CHOICE) != null) {
                    length = Double.parseDouble(feature.getAttribute(LENGTH_ATTR_1ST_CHOICE).toString());
                } else {
                    length = Double.parseDouble(feature.getAttribute(LENGTH_ATTR_2ND_CHOICE).toString())*1000; // Precondition: length in m
                }

                // create basic link
                Link lnk = createLink(net,
                        linkId,
                        feature.getAttribute(FROM_NODE_ID_ATTR).toString(),
                        feature.getAttribute(TO_NODE_ID_ATTR).toString(),
                        length,
                        Double.parseDouble(feature.getAttribute(MAX_SPEED_ATTR).toString()) / 3.6, // Precondition: speed in km/h
                        linkModes
                );

                // add further attributes
                // add width
                double width = readShpFileAttributeAsDouble(feature, WIDTH_ATTR, stdWidth);
                lnk.getAttributes().putAttribute("width", width);

                // add link type
                // the link type needs to be element of the enum LinkType
                String lnkTypeString = readShpFileAttributeAsString(feature, LINK_TYPE_ATTR, "");
                String lnkTypeAssMessage = String.format("The parsed shapefile contains links with linkTypes not recognized: %s", lnkTypeString);
                Assert.isTrue(Arrays.stream(LinkType.values()).anyMatch(el -> el.name().contains(lnkTypeString)), lnkTypeAssMessage);
                LinkType lnkType = LinkType.valueOf(lnkTypeString);
                lnk.getAttributes().putAttribute("linkType", lnkType.toString());

                // add osm id and name
                String osmId = readShpFileAttributeAsString(feature, OSM_ID_ATTR, "");
                lnk.getAttributes().putAttribute("osmID", osmId);
                String osmName = readShpFileAttributeAsString(feature, OSM_NAME_ATTR, "");
                lnk.getAttributes().putAttribute("osmName", osmName);

                // calculate the flow capacity (based on link type and width)
                calculateLinkTypeSpecificAttributes(lnk, lnkType, width);

                // add to network
                net.addLink(lnk);
            }



        }
    }


    private static void calculateLinkTypeSpecificAttributes(Link lnk, LinkType lnkType, double width){
        // ToDo: Include in config group setup
        double v;
        double d;
        switch (lnkType) {
            case Stufen_aufwaerts:
                v = 0.5;
                d = 1.2;
                break;

            case Stufen_abwaerts:
                v = 0.6;
                d = 1.2;
                break;



            default:
                v = 1.3;
                d = 1.0;
        }

        double cap;
        if (lnkType.name().equals("Fussweg_Gleiszugang")){
            // "Gleiszugang" shall have maximum capacity so that combustion effects occur later on
            cap = maxCapacity;

        } else {
            cap = calculateLinkFlowCapacity(v, d, stdWidth, PLATFORM_CLEARANCE_TIME, CAR_SPACE);

            // if in any case capacity calc returns unrealistic small value, minimum capacity is applied
            if (cap < minCapacity){
                cap = minCapacity;
            }

        }

        v = v*1.3; // rise free flow speed by 30% to make passing effects possible
        lnk.setFreespeed(v);
        lnk.setCapacity(cap);
    }


    private static double calculateLinkFlowCapacity(double v, double d, double b_z, double t, double carSpace) {
        /* The following ril 813 rule is applied:
         * b_z = (Q_A / (v*d*t)) + g
         * with b_z := access width
         * Q_A := capacity
         * v := velocity
         * d := people density
         * t := platform clearance time
         * g := walking gauge
         *
         * This leads to the following rearrangement:
         * Q_A = (b_z - g) *v*d*t
         */

        double g = 0.8;
        double cap_ril813 = (b_z-g)*v*d*t;
        double people2CarFactor = d/carSpace;
        return (cap_ril813 * people2CarFactor)*(3600/t); // P/t => P/h
    }


    private static Link createLink(Network net, String linkId, String n1, String n2, double length, double maxSpeed, Set<String> linkModes) {
        Node node1 = net.getNodes().get(Id.createNodeId(n1));
        Node node2 = net.getNodes().get(Id.createNodeId(n2));

        Link lnk = net.getFactory().createLink(Id.createLinkId(linkId), node1, node2);
        lnk.setLength(length); // length in m
        lnk.setFreespeed(maxSpeed);
        lnk.setAllowedModes(linkModes);
        return lnk;
    }


    private static void performCRSCheck(Path shpFile){
        ShapeFileReader shapeFileReader = new ShapeFileReader();
        shapeFileReader.readFileAndInitialize(String.valueOf(shpFile));
        String crsAssMessage = String.format("The parsed shapefile does not have the correct CRS:  %s", COORD_REF_SYS);
        Set<ReferenceIdentifier> referenceIdentifierSet = shapeFileReader.getCoordinateSystem().getCoordinateSystem().getIdentifiers();
        for(var referenceIdentifier: referenceIdentifierSet){
            Assert.isTrue(referenceIdentifier.getCode().equals(COORD_REF_SYS.split(":")[1]), crsAssMessage);
        }
    }

    private static double readShpFileAttributeAsDouble(SimpleFeature feature, String attributeName, double valueIfNull){
        double value;
        if (feature.getAttribute(attributeName) != null) {
            value = Double.parseDouble(feature.getAttribute(attributeName).toString());
        } else {
            value = valueIfNull;
        }
        return value;
    }

    private static String readShpFileAttributeAsString(SimpleFeature feature, String attributeName, String valueIfNull){
        String value;
        if (feature.getAttribute(attributeName) != null) {
            value = feature.getAttribute(attributeName).toString();
        } else {
            value = valueIfNull;
        }
        return value;
    }


    public static void writeNetwork(Network net, Path outputPath) {
        log.info(String.format("Writing network to: %s", outputPath));
        new NetworkWriter(net).write(outputPath.toString());

        log.info("");
        log.info("Finished \uD83C\uDF89");
    }


    private enum LinkType{
        Fussweg_Gleiszugang,
        Fussweg_Zentrallinie,
        Stufen_aufwaerts,
        Stufen_abwaerts,
        Fahrtreppe_aufwaerts,
        gesperrt,
        Fahrtreppe_abwaerts,
        Fussweg,
        Fussweg_Verbinder_Treppe,
        Fussweg_Verbinder_Fuss,
        Fussweg_Verbinder_Aufzug
        }


    private static class Input {
        @Parameter(names = "-ndShpFile")
        private String ndShpFile;

        @Parameter(names = "-lnkShpFile")
        private String lnkShpFile;

        @Parameter(names = "-netOutput")
        private String netOutput;
    }
}
