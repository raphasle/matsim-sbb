/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.population.LegImpl;
import org.matsim.core.population.routes.GenericRouteImpl;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.router.MultiNodeDijkstra;
import org.matsim.pt.router.MultiNodeDijkstra.InitialNode;
import org.matsim.pt.router.PreparedTransitSchedule;
import org.matsim.pt.router.TransitRouter;
import org.matsim.pt.router.TransitRouterNetworkTravelTimeAndDisutility;
import org.matsim.pt.router.TransitTravelDisutility;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.router.TransitRouterConfig;
import org.matsim.pt.router.TransitRouterNetwork;
import org.matsim.pt.router.TransitRouterNetwork.TransitRouterNetworkLink;
import org.matsim.pt.router.TransitRouterNetwork.TransitRouterNetworkNode;


/**
 * Not thread-safe because MultiNodeDijkstra is not. Does not expect the TransitSchedule to change once constructed! michaz '13
 *
 * @author mrieser
 */
public class SBBTransitRouterImpl implements TransitRouter {

    private final TransitRouterNetwork transitNetwork;

    private final MultiNodeDijkstra dijkstra;
    private final TransitRouterConfig config;
    private final TransitTravelDisutility travelDisutility;
    private final TravelTime travelTime;
	static final Logger log = Logger.getLogger(SBBTransitRouterImpl.class);

    private final PreparedTransitSchedule preparedTransitSchedule;

    public SBBTransitRouterImpl(final TransitRouterConfig config, final TransitSchedule schedule) {
        this.preparedTransitSchedule = new PreparedTransitSchedule(schedule);
        TransitRouterNetworkTravelTimeAndDisutility transitRouterNetworkTravelTimeAndDisutility = new TransitRouterNetworkTravelTimeAndDisutility(config, preparedTransitSchedule);
        this.travelTime = transitRouterNetworkTravelTimeAndDisutility;
        this.config = config;
        this.travelDisutility = transitRouterNetworkTravelTimeAndDisutility;
        this.transitNetwork = TransitRouterNetwork.createFromSchedule(schedule, config.getBeelineWalkConnectionDistance());
        this.dijkstra = new MultiNodeDijkstra(this.transitNetwork, this.travelDisutility, this.travelTime);
    }

    public SBBTransitRouterImpl(
            final TransitRouterConfig config,
            final PreparedTransitSchedule preparedTransitSchedule,
            final TransitRouterNetwork routerNetwork,
            final TravelTime travelTime,
            final TransitTravelDisutility travelDisutility) {
        this.config = config;
        this.transitNetwork = routerNetwork;
        this.travelTime = travelTime;
        this.travelDisutility = travelDisutility;
        this.dijkstra = new MultiNodeDijkstra(this.transitNetwork, this.travelDisutility, this.travelTime);
        this.preparedTransitSchedule = preparedTransitSchedule;
    }

    private Map<Node, InitialNode> locateWrappedNearestTransitNodes(Person person, Coord coord, double departureTime) {
        Collection<TransitRouterNetworkNode> nearestNodes = this.transitNetwork.getNearestNodes(coord, this.config.getSearchRadius());
        if (nearestNodes.size() < 2) {
            // also enlarge search area if only one stop found, maybe a second one is near the border of the search area
            TransitRouterNetworkNode nearestNode = this.transitNetwork.getNearestNode(coord);
            double distance = CoordUtils.calcEuclideanDistance(coord, nearestNode.stop.getStopFacility().getCoord());
            nearestNodes = this.transitNetwork.getNearestNodes(coord, distance + this.config.getExtensionRadius());
        }
        Map<Node, InitialNode> wrappedNearestNodes = new LinkedHashMap<>();
        for (TransitRouterNetworkNode node : nearestNodes) {
            Coord toCoord = node.stop.getStopFacility().getCoord();
            double initialTime = getWalkTime(person, coord, toCoord);
            double initialCost = getWalkDisutility(person, coord, toCoord);
            wrappedNearestNodes.put(node, new InitialNode(initialCost, initialTime + departureTime));
        }
        return wrappedNearestNodes;
    }

    private double getWalkTime(Person person, Coord coord, Coord toCoord) {
        return travelDisutility.getTravelTime(person, coord, toCoord);
    }

    private double getWalkDisutility(Person person, Coord coord, Coord toCoord) {
        return travelDisutility.getTravelDisutility(person, coord, toCoord);
    }

    @Override
    public List<Leg> calcRoute(final Coord fromCoord, final Coord toCoord, final double departureTime, final Person person) {
        // find possible start stops
        Map<Node, InitialNode> wrappedFromNodes = this.locateWrappedNearestTransitNodes(person, fromCoord, departureTime);
        // find possible end stops
        Map<Node, InitialNode> wrappedToNodes = this.locateWrappedNearestTransitNodes(person, toCoord, departureTime);

        // find routes between start and end stops
        Path p = this.dijkstra.calcLeastCostPath(wrappedFromNodes, wrappedToNodes, person);

        if (p == null) {
            return null;
        }

        double directWalkCost = getWalkDisutility(person, fromCoord, toCoord);
        double pathCost = p.travelCost + wrappedFromNodes.get(p.nodes.get(0)).initialCost + wrappedToNodes.get(p.nodes.get(p.nodes.size() - 1)).initialCost;

        if (directWalkCost < pathCost) {
            return this.createDirectWalkLegList(null, fromCoord, toCoord);
        }
        return convertPathToLegList(departureTime, p, fromCoord, toCoord, person);
    }

    private List<Leg> createDirectWalkLegList(Person person, Coord fromCoord, Coord toCoord) {
        List<Leg> legs = new ArrayList<>();
        Leg leg = new LegImpl(TransportMode.transit_walk);
        double walkTime = getWalkTime(person, fromCoord, toCoord);
        leg.setTravelTime(walkTime);
        Route walkRoute = new GenericRouteImpl(null, null);
        walkRoute.setTravelTime(walkTime);
        leg.setRoute(walkRoute);
        legs.add(leg);
        return legs;
    }

    protected List<Leg> convertPathToLegList(double departureTime, Path path, Coord fromCoord, Coord toCoord, Person person) {
	    // now convert the path back into a series of legs with correct routes
	    double time = departureTime;
	    List<Leg> legs = new ArrayList<>();
	    Leg leg;
	    TransitLine line = null;
	    TransitRoute route = null;
	    TransitStopFacility accessStop = null;
	    TransitRouteStop transitRouteStart = null;
	    TransitRouterNetworkLink prevLink = null;
		double currentDistance = 0;
	    int transitLegCnt = 0;
	    for (Link ll : path.links) {
		    TransitRouterNetworkLink link = (TransitRouterNetworkLink) ll;
		    if (link.getLine() == null) {
			    // (it must be one of the "transfer" links.) finish the pt leg, if there was one before...
			    TransitStopFacility egressStop = link.fromNode.stop.getStopFacility();
			    if (route != null) {
				    leg = new LegImpl(TransportMode.pt);
				    ExperimentalTransitRoute ptRoute = new ExperimentalTransitRoute(accessStop, line, route, egressStop);
				    double arrivalOffset = (link.getFromNode().stop.getArrivalOffset() != Time.UNDEFINED_TIME) ? link.fromNode.stop.getArrivalOffset() : link.fromNode.stop.getDepartureOffset();
				    double arrivalTime = this.preparedTransitSchedule.getNextDepartureTime(route, transitRouteStart, time) + (arrivalOffset - transitRouteStart.getDepartureOffset());
				    ptRoute.setTravelTime(arrivalTime - time);
					ptRoute.setDistance( currentDistance );
				    leg.setRoute(ptRoute);
				    leg.setTravelTime(arrivalTime - time);
				    time = arrivalTime;
				    legs.add(leg);
				    transitLegCnt++;
				    accessStop = egressStop;
			    }
			    line = null;
			    route = null;
			    transitRouteStart = null;
				currentDistance = link.getLength();
		    } else {
			    // (a real pt link)
				currentDistance += link.getLength();
			    if (link.getRoute() != route) {
				    // the line changed
				    TransitStopFacility egressStop = link.fromNode.stop.getStopFacility();
				    if (route == null) {
					    // previously, the agent was on a transfer, add the walk leg
					    transitRouteStart = ((TransitRouterNetworkLink) ll).getFromNode().stop;
					    if (accessStop != egressStop) {
						    if (accessStop != null) {
							    leg = new LegImpl(TransportMode.transit_walk);
							    double walkTime = getWalkTime(person, accessStop.getCoord(), egressStop.getCoord());
							    Route walkRoute = new GenericRouteImpl(accessStop.getLinkId(), egressStop.getLinkId());
							    walkRoute.setTravelTime(walkTime);
								walkRoute.setDistance( currentDistance );
							    leg.setRoute(walkRoute);
							    leg.setTravelTime(walkTime);
							    time += walkTime;
							    legs.add(leg);
						    } else { // accessStop == null, so it must be the first walk-leg
								leg = new LegImpl(TransportMode.transit_walk);
								double walkTime = getWalkTime(person, fromCoord, egressStop.getCoord());
								Route walkRoute = new GenericRouteImpl(null, egressStop.getLinkId());
							    walkRoute.setTravelTime(walkTime);
								walkRoute.setDistance( currentDistance );
							    leg.setRoute(walkRoute);
								leg.setTravelTime(walkTime);
								time += walkTime;
								legs.add(leg);
						    }
					    }
						currentDistance = 0;
				    }
				    line = link.getLine();
				    route = link.getRoute();
				    accessStop = egressStop;
			    }
		    }
		    prevLink = link;
	    }
	    if (route != null) {
		    // the last part of the path was with a transit route, so add the pt-leg and final walk-leg
		    leg = new LegImpl(TransportMode.pt);
		    TransitStopFacility egressStop = prevLink.toNode.stop.getStopFacility();
		    ExperimentalTransitRoute ptRoute = new ExperimentalTransitRoute(accessStop, line, route, egressStop);
			ptRoute.setDistance( currentDistance );
		    leg.setRoute(ptRoute);
		    double arrivalOffset = ((prevLink).toNode.stop.getArrivalOffset() != Time.UNDEFINED_TIME) ?
				    (prevLink).toNode.stop.getArrivalOffset()
				    : (prevLink).toNode.stop.getDepartureOffset();
			double arrivalTime = this.preparedTransitSchedule.getNextDepartureTime(route, transitRouteStart, time) + (arrivalOffset - transitRouteStart.getDepartureOffset());
			leg.setTravelTime(arrivalTime - time);
			ptRoute.setTravelTime( arrivalTime - time );
			legs.add(leg);
			transitLegCnt++;
			accessStop = egressStop;
	    }
	    if (prevLink != null) {
		    leg = new LegImpl(TransportMode.transit_walk);
		    double walkTime;
		    if (accessStop == null) {
			    walkTime = getWalkTime(person, fromCoord, toCoord);
		    } else {
			    walkTime = getWalkTime(person, accessStop.getCoord(), toCoord);
		    }
		    leg.setTravelTime(walkTime);
		    legs.add(leg);
	    }
	    if (transitLegCnt == 0) {
		    // it seems, the agent only walked
		    legs.clear();
		    leg = new LegImpl(TransportMode.transit_walk);
		    double walkTime = getWalkTime(person, fromCoord, toCoord);
		    leg.setTravelTime(walkTime);
		    legs.add(leg);
	    }
	    return legs;
    }

    public TransitRouterNetwork getTransitRouterNetwork() {
        return this.transitNetwork;
    }

    protected TransitRouterNetwork getTransitNetwork() {
        return transitNetwork;
    }

    protected MultiNodeDijkstra getDijkstra() {
        return dijkstra;
    }

    protected TransitRouterConfig getConfig() {
        return config;
    }

}