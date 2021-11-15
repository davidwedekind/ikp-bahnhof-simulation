package org.matsim.ikp.prepare;

import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
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
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class CreateNetworkFromVisumShpFile {
    private static final Logger log = Logger.getLogger(CreateNetworkFromVisumShpFile.class);

    public static String COORD_REF_SYS = "epsg:32632";
    public static String NODE_ID_ATTR_NAME = "NO";
    public static String NODE_LN_ATTR_NAME = "NAME";
    public static String LINK_ID_ATTR_NAME = "NO";

    // ToDo: Wie gehe ich mit max speed um?
    public static double MAX_SPEED = 5.5;
    public static Map<Integer, Double> MAX_SPEED_BY_LINK_TYPE =
            Map.of(100,1.5, 200, 2.5);

    public static Map<String, String> SHP_FILE_FEAT_ATTR_TO_LINK_ATTR_MAP =
            Map.of("width","GEOM_WIDTH", "code", "CODE");



    public static void main(String[] args) {

        Path shp = Path.of("C:\\Users\\david\\Documents\\03_Repositories\\db-input\\kk-3320-koeln_hbf_M0U0_link\\kk-3320-koeln_hbf_M0U0_link\\kk-3320-koeln_hbf_M0U0_node.SHP");
        Path output = Path.of("C:\\Users\\david\\Documents\\03_Repositories\\db-input\\kk-3320-koeln_hbf_M0U0_link\\kk-3320-koeln_hbf_M0U0_link\\network.xml");

        Set<String> linkModes = Set.of("walk_1_25", "walk_1_3", "walk_1_35");
        Network net = createNetwork(shp, shp, linkModes);
        writeNetwork(net, output);

    }

    public static Network createNetwork(Path nodeShpFile, Path linkShpFile, Set<String> linkModes){
        log.info("Start creating network");
        Network net = NetworkUtils.createNetwork();
        NetworkFactory fac = net.getFactory();
        net.getAttributes().putAttribute("coordinateReferenceSystem", COORD_REF_SYS);
        addNodes(fac, net, nodeShpFile);
        addLinks(fac, net, linkShpFile, linkModes);
        return net;
    }


    private static void addNodes(NetworkFactory fac, Network net, Path nodeShpFile){
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
                    fac,
                    feature.getAttribute(NODE_ID_ATTR_NAME).toString(),
                    feature.getAttribute(NODE_LN_ATTR_NAME).toString(),
                    pt.getX(),
                    pt.getY());
        }
    }


    public static void createNode(Network net, NetworkFactory fac, String nodeId, String longName, double x, double y){
        Node nd = fac.createNode(Id.createNodeId(nodeId), new Coord(x, y));
        nd.getAttributes().putAttribute("longName", longName);
        net.addNode(nd);
    }


    private static void addLinks(NetworkFactory fac, Network net, Path linkShpFile, Set<String> linkModes) {
        log.info("Add links");
        log.info(String.format("Read links from shapefile: %s", linkShpFile));
        performCRSCheck(linkShpFile);

        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(String.valueOf(linkShpFile));
        String linkAssMessage = "The parsed shapefile contains geometries which are not of type 'lineString'.";
        // create one link per lineString geometry feature in shapefile
        // Precondition: export directional vertices from Visum
        for(var feature: features) {
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            Assert.isTrue(geometry instanceof LineString, linkAssMessage);
            LineString ls = (LineString) geometry;

            double length = convertLength(feature.getAttribute("LENGTH").toString());
            Link link = createLink(net,
                    fac,
                    feature.getAttribute(LINK_ID_ATTR_NAME).toString(),
                    feature.getAttribute("FROMNODENO").toString(),
                    feature.getAttribute("TONODENO").toString(),
                    length,
                    Double.parseDouble(feature.getAttribute("").toString()), // ToDo: Methode zur Berechnung der Kapazität oder ist die als Attribut enthalten?
                    MAX_SPEED,
                    linkModes
                    );


        }
    }


    private static Link createLink(Network net, NetworkFactory fac, String linkId, String n1, String n2, double length, double cap, double maxSpeed, Set<String> linkModes) {
        Node node1 = net.getNodes().get(Id.createNodeId(n1));
        Node node2 = net.getNodes().get(Id.createNodeId(n2));

        Link lnk = fac.createLink(Id.createLinkId(linkId), node1, node2);
        lnk.setLength(length); // length in m
        lnk.setCapacity(cap); // capacity in veh/h =>
        lnk.setFreespeed(maxSpeed);
        lnk.setAllowedModes(linkModes);
        // ToDo: Wie gehe ich mit additional Attr um?
        //for (var entry: SHP_FILE_FEAT_ATTR_TO_LINK_ATTR_MAP.entrySet()){
            //lnk.getAttributes().putAttribute(entry.getKey(), feature.getAttribute(entry.getValue()));
        //}
        net.addLink(lnk);
        return lnk;
    }

    private static double convertLength(String length){
        String manipulated = length.replace("km", "").replace(",", ".");
        return Double.parseDouble(manipulated) * 1000; // matsim takes length in metres
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




    public static void writeNetwork(Network net, Path outputPath) {
        log.info(String.format("Writing network to: %s", outputPath));
        new NetworkWriter(net).write(outputPath.toString());

        log.info("");
        log.info("Finished \uD83C\uDF89");
    }
}
