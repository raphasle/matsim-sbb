/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.preparation;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.core.utils.misc.Counter;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.utils.objectattributes.ObjectAttributesUtils;
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehicleWriterV1;
import org.matsim.vehicles.Vehicles;
import org.opengis.feature.simple.SimpleFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Cutter {

    private final static Logger log = Logger.getLogger(Cutter.class);

    private static final String ATT_SIMBA_CH_PERIMETER = "08_SIMBA_CH_Perimeter";
    private static final int VAL_SIMBA_CH_PERIMETER = 1;
    private static final String ATT_SIMBATEILGEBIETPERIMETER = "10_SIMBA_TG_Perimeter";

    private static final String PLANS_OUT = "plans.xml.gz";
    private static final String PERSON_ATTRIBUTES_OUT = "personAttributes.xml.gz";
    private static final String SCHEDULE_OUT = "transitSchedule.xml.gz";
    private static final String VEHICLES_OUT = "transitVehicles.xml.gz";
    private static final String NETWORK_OUT = "network.xml.gz";

    private Scenario scenario;
    private CutterConfigGroup cutterConfig;
    private GeometryFactory geometryFactory = new GeometryFactory();
    Collection<SimpleFeature> features = null;
    private Map<Coord, Boolean> coordCache;

    private Coord center;
    private int radius;
    private boolean useShapeFile;

    public static void main(final String[] args) {
        final Config config = ConfigUtils.loadConfig(args[0], new CutterConfigGroup());
        Cutter cutter = new Cutter(config);
        Scenario filteredScenario = cutter.getFilteredScenario();
        cutter.write(filteredScenario);
    }

    public Cutter(Config config) {
        this(ScenarioUtils.loadScenario(config));
    }

    public Cutter(Scenario scenario)    {
        this.cutterConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), CutterConfigGroup.class);
        this.scenario = scenario;

        this.coordCache = new HashMap<>();

        if (this.cutterConfig.getUseShapeFile()) {
            this.useShapeFile = true;
            ShapeFileReader shapeFileReader = new ShapeFileReader();
            shapeFileReader.readFileAndInitialize(this.cutterConfig.getPathToShapeFile());
            this.features = shapeFileReader.getFeatureSet();
        } else {
            this.center = new Coord(this.cutterConfig.getxCoordCenter(), this.cutterConfig.getyCoordCenter());
            this.radius = this.cutterConfig.getRadius();
        }
    }

    public Scenario getFilteredScenario()   {
        Population filteredPopulation = this.geographicallyFilterPopulation();
        TransitSchedule filteredSchedule = this.cutPT(this.scenario, filteredPopulation);
        Vehicles filteredVehicles = this.cleanVehicles(this.scenario, filteredSchedule);
        Network filteredOnlyCarNetwork = this.getOnlyCarNetwork(filteredPopulation);
        Network filteredNetwork = this.cutNetwork(this.scenario, filteredSchedule, filteredOnlyCarNetwork);
         /*
        Households filteredHouseholds = cutter.filterHouseholdsWithPopulation();
        ActivityFacilities filteredFacilities = cutter.filterFacilitiesWithPopulation();
        */

        MutableScenario filteredScenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig());
        filteredScenario.setPopulation(filteredPopulation);
        filteredScenario.setNetwork(filteredNetwork);
        filteredScenario.setTransitSchedule(filteredSchedule);
        filteredScenario.setTransitVehicles(filteredVehicles);

        return filteredScenario;
    }

    public void write(Scenario scenario)    {
        String output = this.cutterConfig.getPathToTargetFolder();
        try {
            Files.createDirectories(Paths.get(output));
        } catch (IOException e) {
            log.error("Could not create output directory " + output, e);
        }

        new PopulationWriter(scenario.getPopulation()).write(output + File.separator + PLANS_OUT);
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(output + File.separator + SCHEDULE_OUT);
        new VehicleWriterV1(scenario.getTransitVehicles()).writeFile(output + File.separator + VEHICLES_OUT);
        new NetworkWriter(scenario.getNetwork()).write(output + File.separator + NETWORK_OUT);
        new ObjectAttributesXmlWriter(scenario.getPopulation().getPersonAttributes()).writeFile( output + File.separator + PERSON_ATTRIBUTES_OUT);
        /*
        F2LCreator.createF2L(filteredFacilities, filteredOnlyCarNetwork, cutterConfig.getPathToTargetFolder() + File.separator + FACILITIES2LINKS);
        writeNewFiles(cutterConfig.getPathToTargetFolder() + File.separator, cutter.scenario,
                filteredPopulation, filteredHouseholds, filteredFacilities, filteredSchedule, filteredVehicles,
                filteredNetwork, cutter.createConfig(cutterConfig));
        cutter.cutPTCounts(filteredNetwork, cutterConfig);
        */
    }

    private Network getOnlyCarNetwork(Population filteredPopulation) {
        Set<Id<Link>> linksToKeep = new HashSet<>();
        for (Person p: filteredPopulation.getPersons().values()) {
            for(PlanElement pe: p.getSelectedPlan().getPlanElements()){
                if (pe instanceof Leg) {
                    Leg leg = (Leg) pe;
                    if(leg.getRoute() == null)
                        continue;

                    linksToKeep.add(leg.getRoute().getStartLinkId());
                    linksToKeep.add(leg.getRoute().getEndLinkId());
                    if(leg.getRoute() instanceof NetworkRoute){
                        NetworkRoute route = (NetworkRoute) leg.getRoute();
                        linksToKeep.addAll(route.getLinkIds());
                    }
                }
                else if (pe instanceof Activity)    {
                    linksToKeep.add(((Activity) pe).getLinkId());
                }
            }
        }

        Network carNetworkToKeep = NetworkUtils.createNetwork();
        for (Link link : this.scenario.getNetwork().getLinks().values()) {
            if (linksToKeep.contains(link.getId())) addLink(carNetworkToKeep, link);
            else if (link.getAllowedModes().contains("car") && (link.getCapacity() >= 10000)) addLink(carNetworkToKeep, link);
            else {
                if (this.useShapeFile) {
                    Point point = this.geometryFactory.createPoint( new Coordinate(link.getCoord().getX(), link.getCoord().getY()));
                    for (SimpleFeature feature : this.features) {
                        MultiPolygon p = (MultiPolygon) feature.getDefaultGeometry();
                        if (p.distance(point) <= 5000) {
                            // CRS have to be such that distance returns meter-units!
                            addLink(carNetworkToKeep, link);
                            break;
                        }
                    }
                }
                else {
                    if (CoordUtils.calcEuclideanDistance(this.center, link.getCoord()) <= this.radius + 5000) addLink(carNetworkToKeep, link); // and we keep all links within radius + 5km)
                }
            }
        }
        return carNetworkToKeep;
    }

    private static Network cutNetwork(Scenario scenario, TransitSchedule filteredSchedule, Network filteredOnlyCarNetwork) {
        Network filteredNetwork = NetworkUtils.createNetwork();
        Set<Id<Link>> linksToKeep = getPTLinksToKeep(filteredSchedule);
        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (linksToKeep.contains(link.getId()) || // we keep all links we need for pt
                    filteredOnlyCarNetwork.getLinks().containsKey(link.getId())) {
                addLink(filteredNetwork, link);
            }
        }
        return filteredNetwork;
    }

    private static Set<Id<Link>> getPTLinksToKeep(TransitSchedule filteredSchedule) {
        Set<Id<Link>> linksToKeep = new HashSet<>();
        for (TransitLine transitLine : filteredSchedule.getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                linksToKeep.add(transitRoute.getRoute().getStartLinkId());
                linksToKeep.addAll(transitRoute.getRoute().getLinkIds());
                linksToKeep.add(transitRoute.getRoute().getEndLinkId());
            }
        }
        return linksToKeep;
    }

    private static void addLink(Network network, Link link) {
        if (!network.getNodes().containsKey(link.getFromNode().getId())) {
            Node node = network.getFactory().createNode(link.getFromNode().getId(), link.getFromNode().getCoord());
            network.addNode(node);
        }
        if (!network.getNodes().containsKey(link.getToNode().getId())) {
            Node node = network.getFactory().createNode(link.getToNode().getId(), link.getToNode().getCoord());
            network.addNode(node);
        }
        network.addLink(link);
        link.setFromNode(network.getNodes().get(link.getFromNode().getId()));
        link.setToNode(network.getNodes().get(link.getToNode().getId()));
    }

    private TransitSchedule cutPT(Scenario scenario, Population filteredPopulation) {
        Map<Id<TransitLine>, Set<Id<TransitRoute>>> usedTransitRouteIds = new HashMap<>();
        for (Person p: filteredPopulation.getPersons().values()) {
            for (PlanElement pe : p.getSelectedPlan().getPlanElements()) {
                if (pe instanceof Leg) {
                    Route route = ((Leg) pe).getRoute();
                    if (route instanceof ExperimentalTransitRoute) {
                        ExperimentalTransitRoute myRoute = (ExperimentalTransitRoute) route;
                        if(!usedTransitRouteIds.containsKey(myRoute.getLineId())){
                            usedTransitRouteIds.put(myRoute.getLineId(), new HashSet<>());
                        }
                        usedTransitRouteIds.get(myRoute.getLineId()).add(myRoute.getRouteId());
                    }
                }
            }
        }

        TransitSchedule filteredSchedule = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getTransitSchedule();
        Set<Id<TransitStopFacility>> stopsToKeep = new HashSet<>();

        for (TransitLine transitLine : scenario.getTransitSchedule().getTransitLines().values()) {
            Set<Id<TransitRoute>> _routes = new HashSet<>();

            if(usedTransitRouteIds.containsKey(transitLine.getId())){
                _routes = usedTransitRouteIds.get(transitLine.getId());
                log.info(transitLine+": "+_routes);
            }

            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                if(_routes.contains(transitRoute.getId())){
                    Id<TransitLine> newLineId = addLine(filteredSchedule, transitLine);
                    filteredSchedule.getTransitLines().get(newLineId).addRoute(transitRoute);
                    this.addStopFacilities(filteredSchedule, transitRoute, stopsToKeep);
                }

                else {
                    for (TransitRouteStop transitStop : transitRoute.getStops()) {
                        if (this.inArea(transitStop.getStopFacility().getCoord())) {
                            Id<TransitLine> newLineId = addLine(filteredSchedule, transitLine);
                            filteredSchedule.getTransitLines().get(newLineId).addRoute(transitRoute);
                            this.addStopFacilities(filteredSchedule, transitRoute, stopsToKeep);
                            break;
                        }
                    }
                }
            }
        }

        MinimalTransferTimes unfilteredMTT =  scenario.getTransitSchedule().getMinimalTransferTimes();
        MinimalTransferTimes filteredMTT = filteredSchedule.getMinimalTransferTimes();
        MinimalTransferTimes.MinimalTransferTimesIterator itr = unfilteredMTT.iterator();
        while(itr.hasNext()) {
            itr.next();
            if(stopsToKeep.contains(itr.getFromStopId()) && stopsToKeep.contains(itr.getToStopId()))
                filteredMTT.set(itr.getFromStopId(), itr.getToStopId(), itr.getSeconds());
        }

        return filteredSchedule;
    }

    private void addStopFacilities(TransitSchedule schedule, TransitRoute transitRoute, Set<Id<TransitStopFacility>> stopsToKeep) {
        for (TransitRouteStop newStop : transitRoute.getStops()) {
            if (!schedule.getFacilities().containsKey(newStop.getStopFacility().getId())) {
                schedule.addStopFacility(newStop.getStopFacility());
                stopsToKeep.add(newStop.getStopFacility().getId());
                if(this.inArea(newStop.getStopFacility().getCoord()) &&
                        (int) newStop.getStopFacility().getAttributes().getAttribute(ATT_SIMBA_CH_PERIMETER) == VAL_SIMBA_CH_PERIMETER)
                    newStop.getStopFacility().getAttributes().putAttribute(ATT_SIMBATEILGEBIETPERIMETER, 1);
                else
                    newStop.getStopFacility().getAttributes().putAttribute(ATT_SIMBATEILGEBIETPERIMETER, 0);
            }
        }
    }

    private static Id<TransitLine> addLine(TransitSchedule schedule, TransitLine transitLine) {
        Id<TransitLine> newLineId = Id.create(transitLine.getId().toString(), TransitLine.class);
        if (!schedule.getTransitLines().containsKey(newLineId)) {
            TransitLine newLine = schedule.getFactory().createTransitLine(newLineId);
            schedule.addTransitLine(newLine);
            newLine.setName(transitLine.getName());
        }
        return newLineId;
    }

    private static Vehicles cleanVehicles(Scenario scenario, TransitSchedule transitSchedule) {
        Vehicles filteredVehicles = VehicleUtils.createVehiclesContainer();
        for (TransitLine line : transitSchedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (Departure departure : route.getDepartures().values()) {
                    Vehicle vehicleToKeep = scenario.getTransitVehicles().getVehicles().get(departure.getVehicleId());
                    if (!filteredVehicles.getVehicleTypes().containsValue(vehicleToKeep.getType())) {
                        filteredVehicles.addVehicleType(vehicleToKeep.getType());
                    }
                    filteredVehicles.addVehicle(vehicleToKeep);
                }
            }
        }
        return filteredVehicles;
    }


    private static List<Id<Link>> getPuTLinksRoute(ExperimentalTransitRoute route, TransitSchedule transit){
        TransitRoute tr = transit.getTransitLines().get(route.getLineId()).getRoutes().get(route.getRouteId());
        List<Id<Link>> linkIds = new ArrayList<>();
        Boolean record = false;
        for (Id<Link> linkId: tr.getRoute().getLinkIds()){
            if (linkId == route.getStartLinkId()) record = true;
            if (record){
                linkIds.add(linkId);
            }
            if (linkId == route.getEndLinkId()) record = false;
        }
        return linkIds;
    }


    private Boolean linksInArea(List<Id<Link>> linkIds){
        for(Id<Link> linkId: linkIds){
            Link link = this.scenario.getNetwork().getLinks().get(linkId);
            if(this.inArea(link.getFromNode().getCoord()) || this.inArea(link.getToNode().getCoord())){
                return true;
            }
        }
        return false;
    }

    private Boolean intersects(Leg leg, TransitSchedule transit){
        boolean intersection = false;
        List<Id<Link>> linkIds = new ArrayList<>();
        if(leg.getRoute() instanceof ExperimentalTransitRoute){
            ExperimentalTransitRoute route = (ExperimentalTransitRoute) leg.getRoute();
            if (route == null){
                log.info("Population should be routed. I will ignore this leg"+ leg);
            }
            else {
                linkIds = getPuTLinksRoute(route, transit);
            }
        }
        else if (leg.getRoute().getRouteType().equals("generic")){
        }
        else {
            NetworkRoute route = (NetworkRoute) leg.getRoute();
            if (route == null){
                log.info("Population should be routed. I will ignore this leg"+ leg);
            }
            else {
                linkIds = route.getLinkIds();
            }
        }

        if(this.linksInArea(linkIds)){
            intersection = true;
        }
        return intersection;
    }

    private Population geographicallyFilterPopulation() {
        Population filteredPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Population population = this.scenario.getPopulation();
        Counter counter = new Counter(" person # ");
        boolean hasActivitiesInside;
        boolean hasActivitiesOutside;
        boolean intersectsPerimeter;

        for (Person p : population.getPersons().values()) {
            counter.incCounter();
            if (p.getSelectedPlan() != null) {
                hasActivitiesInside = false;
                hasActivitiesOutside = false;
                intersectsPerimeter = false;

                for (PlanElement pe : p.getSelectedPlan().getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity act = (Activity) pe;

                        if (this.inArea(act.getCoord())) {
                            hasActivitiesInside = true;
                        } else {
                            hasActivitiesOutside = true;
                        }
                    } else if (pe instanceof Leg) {
                        Leg leg = (Leg) pe;

                        if (leg.getRoute() == null) {
                            continue;
                        }
                        if (intersects(leg, this.scenario.getTransitSchedule())) {
                            intersectsPerimeter = true;
                        }
                    }
                }

                if (hasActivitiesInside || intersectsPerimeter) {
                    if(hasActivitiesOutside) {
                        for (Map.Entry<String, Map<String, String>> attributeEntry : this.cutterConfig.getAttributeMap().entrySet()) {
                            String attributeName = attributeEntry.getKey(); // subpopulation
                            Map<String, String> attributeValues = attributeEntry.getValue(); // e.g. regular -> cb

                            Object attributeValueOld = population.getPersonAttributes().getAttribute(p.getId().toString(), attributeName); // e.g regular
                            if ((attributeValueOld != null) && attributeValues.get(attributeValueOld.toString()) != null) {
                                String attributeValueNew = attributeValues.get(attributeValueOld.toString());
                                population.getPersonAttributes().putAttribute(p.getId().toString(), attributeName, attributeValueNew);
                            }
                        }
                    }
                    filteredPopulation.addPerson(p);
                    ObjectAttributesUtils.copyAllAttributes(population.getPersonAttributes(), filteredPopulation.getPersonAttributes(), p.getId().toString());
                }
            }
        }
        log.info("filtered population:" + filteredPopulation.getPersons().size());
        return filteredPopulation;
    }

    /*
    private Households filterHouseholdsWithPopulation() {
        Households filteredHouseholds = new HouseholdsImpl();

        for (Household household : scenario.getHouseholds().getHouseholds().values()) {
            Set<Id<Person>> personIdsToRemove = new HashSet<>();
            for (Id<Person> personId : household.getMemberIds()) {
                if (!filteredAgents.keySet().contains(personId)) {
                    personIdsToRemove.add(personId);
                }
            }
            for (Id<Person> personId : personIdsToRemove) {
                household.getMemberIds().remove(personId);
            }
            if (!household.getMemberIds().isEmpty()) {
                filteredHouseholds.getHouseholds().put(household.getId(), household);
            } else {
                scenario.getHouseholds().getHouseholdAttributes().removeAllAttributes(household.getId().toString());
            }
        }

        return filteredHouseholds;
    }

    private ActivityFacilities filterFacilitiesWithPopulation() {
        ActivityFacilities filteredFacilities = FacilitiesUtils.createActivityFacilities();

        for (Person person : filteredAgents.values()) {
            if (person.getSelectedPlan() != null) {
                for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity act = (Activity) pe;
                        if (act.getFacilityId() != null && !filteredFacilities.getFacilities().containsKey(act.getFacilityId())) {
                            filteredFacilities.addActivityFacility(scenario.getActivityFacilities().getFacilities().get(act.getFacilityId()));
                        }
                    }
                }
            }
        }

        return filteredFacilities;
    }
    */

    private boolean inArea(Coord coord) {
        if (coordCache.containsKey(coord)) {
            return coordCache.get(coord);
        } else {
            boolean coordIsInArea = false;
            if (this.useShapeFile) {
                for (SimpleFeature feature : features) {
                    MultiPolygon p = (MultiPolygon) feature.getDefaultGeometry();
                    Point point = geometryFactory.createPoint( new Coordinate(coord.getX(), coord.getY()));
                    coordIsInArea = p.contains(point);
                    if (coordIsInArea) break;
                }
            }
            else {
                coordIsInArea = CoordUtils.calcEuclideanDistance(center, coord) <= radius;
            }
            coordCache.put(coord, coordIsInArea);
            return coordIsInArea;
        }
    }

    public static class CutterConfigGroup extends ReflectiveConfigGroup {
        public static final String GROUP_NAME = "cutter";

        private String pathToInputScnearioFolder;
        private String pathToTargetFolder = "Cutter";

        private Map<String, Map<String, String>> attributeMap;

        private double xCoordCenter = 598720.4;
        private double yCoordCenter = 122475.3;
        private int radius = 30000;
        private boolean useShapeFile = false;
        private String pathToShapeFile = null;

        public CutterConfigGroup() {
            super(GROUP_NAME);
        }

        @StringGetter("inputScenarioFolder")
        String getPathToInputScenarioFolder() {
            return pathToInputScnearioFolder;
        }

        @StringSetter("inputScenarioFolder")
        void setPathToInputScenarioFolder(String pathToInputScenarioFolder) {
            this.pathToInputScnearioFolder = pathToInputScenarioFolder;
        }

        @StringGetter("outputFolder")
        String getPathToTargetFolder() {
            return pathToTargetFolder;
        }

        @StringSetter("outputFolder")
        void setPathToTargetFolder(String pathToTargetFolder) {
            this.pathToTargetFolder = pathToTargetFolder;
        }

        @StringGetter("xCoordCenter")
        double getxCoordCenter() {
            return xCoordCenter;
        }

        @StringSetter("xCoordCenter")
        void setxCoordCenter(double xCoordCenter) {
            this.xCoordCenter = xCoordCenter;
        }

        @StringGetter("yCoordCenter")
        double getyCoordCenter() {
            return yCoordCenter;
        }

        @StringSetter("yCoordCenter")
        void setyCoordCenter(double yCoordCenter) {
            this.yCoordCenter = yCoordCenter;
        }

        @StringGetter("radius")
        int getRadius() {
            return radius;
        }

        @StringSetter("radius")
        void setRadius(int radius) {
            this.radius = radius;
        }

        @StringGetter("useShapeFile")
        boolean getUseShapeFile() {
            return useShapeFile;
        }

        @StringSetter("useShapeFile")
        void setUseShapeFile(boolean useShapeFile) {
            this.useShapeFile = useShapeFile;
        }

        @StringGetter("pathToShapeFile")
        String getPathToShapeFile() {
            return pathToShapeFile;
        }

        @StringSetter("pathToShapeFile")
        void setPathToShapeFile(String pathToShapeFile) {
            this.pathToShapeFile = pathToShapeFile;
        }

        public Map<String, Map<String, String>> getAttributeMap() {
            Map<String, Map<String, String>> attributeMap = new TreeMap<>();
            Map<String, String> subpopulationMap = new TreeMap<>();
            subpopulationMap.put("regular", "cb");
            subpopulationMap.put("cb", "cb");
            subpopulationMap.put("freight", "cb_freight");
            attributeMap.put("subpopulation", subpopulationMap);

            return attributeMap;
        }
    }
}
