package ch.sbb.matsim.analysis.VisumPuTSurvey;

import ch.sbb.matsim.analysis.travelcomponents.Journey;
import ch.sbb.matsim.analysis.travelcomponents.TravellerChain;
import ch.sbb.matsim.analysis.travelcomponents.Trip;
import ch.sbb.matsim.csv.CSVWriter;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.utils.io.UncheckedIOException;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VisumPuTSurvey {

    private static final String FILENAME = "matsim_put_survey.att";

    private static final String COL_PATH_ID = "$OEVTEILWEG:DATENSATZNR";
    private static final String COL_LEG_ID = "TWEGIND";
    private static final String COL_FROM_STOP = "VONHSTNR";
    private static final String COL_TO_STOP = "NACHHSTNR";
    private static final String COL_VSYSCODE = "VSYSCODE";
    private static final String COL_LINNAME = "LINNAME";
    private static final String COL_LINROUTENAME = "LINROUTENAME";
    private static final String COL_RICHTUNGSCODE = "RICHTUNGSCODE";
    private static final String COL_FZPROFILNAME = "FZPNAME";
    private static final String COL_TEILWEG_KENNUNG = "TEILWEG-KENNUNG";
    private static final String COL_EINHSTNR = "EINHSTNR";
    private static final String COL_EINHSTABFAHRTSTAG = "EINHSTABFAHRTSTAG";
    private static final String COL_EINHSTABFAHRTSZEIT = "EINHSTABFAHRTSZEIT";
    private static final String COL_PFAHRT = "PFAHRT";
    private static final String COL_SUBPOP = "SUBPOP";
    private static final String[] COLUMNS = new String[] { COL_PATH_ID, COL_LEG_ID, COL_FROM_STOP, COL_TO_STOP, COL_VSYSCODE, COL_LINNAME, COL_LINROUTENAME, COL_RICHTUNGSCODE, COL_FZPROFILNAME,
            COL_TEILWEG_KENNUNG, COL_EINHSTNR, COL_EINHSTABFAHRTSTAG, COL_EINHSTABFAHRTSZEIT, COL_PFAHRT, COL_SUBPOP };

    private static final String HEADER = "$VISION\n* VisumInst\n* 10.11.06\n*\n*\n* Tabelle: Versionsblock\n$VERSION:VERSNR;FILETYPE;LANGUAGE;UNIT\n4.00;Att;DEU;KM\n*\n*\n* Tabelle: ÖV-Teilwege\n";

    private static final String STOP_NO = "02_Stop_No";
    private static final String TSYS_CODE = "09_TSysCode";
    private static final String DIRECTION_CODE = "04_DirectionCode";
    private static final String TRANSITLINE = "02_TransitLine";
    private static final String LINEROUTENAME = "03_LineRouteName";
    private static final String FZPNAME = "05_Name";

    final private Map<Id, TravellerChain> chains;
    final private Map<Id, PTVehicle> ptVehicles = new HashMap<>();
    final private TransitSchedule transitSchedule;
    final private Scenario scenario;
    private Double scaleFactor;

    private final static Logger log = Logger.getLogger(VisumPuTSurvey.class);

    public VisumPuTSurvey(Map<Id, TravellerChain> chains, Scenario scenario, Double scaleFactor) {
        this.chains = chains;
        readVehicles(scenario.getTransitSchedule());
        this.scenario = scenario;
        this.transitSchedule = scenario.getTransitSchedule();
        this.scaleFactor = scaleFactor;
    }

    private void readVehicles(TransitSchedule transitSchedule) {
        for (TransitLine tL : transitSchedule.getTransitLines().values()) {
            for (TransitRoute tR : tL.getRoutes().values()) {
                for (Departure dep : tR.getDepartures().values()) {
                    Id<org.matsim.vehicles.Vehicle> vehicleId = dep.getVehicleId();
                    if (ptVehicles.containsKey(vehicleId)) {
                        log.error("vehicleId already in Map: " + vehicleId);
                    } else {
                        this.ptVehicles.put(vehicleId, new PTVehicle(tL, tR));
                    }
                }
            }
        }
    }

    public void write(String path) {
        final String filepath = path + FILENAME;
        log.info("write Visum PuT Survey File to " + filepath);

        try (CSVWriter writer = new CSVWriter(HEADER, COLUMNS, filepath)) {
            for (Map.Entry<Id, TravellerChain> entry : chains.entrySet()) {
                String pax_id = entry.getKey().toString();
                TravellerChain chain = entry.getValue();
                for (Journey journey : chain.getJourneys()) {
                    Integer i = 1;
                    for (Trip trip : journey.getTrips()) {
                        if (this.ptVehicles.containsKey(trip.getVehicleId())) {

                            writer.set(COL_PATH_ID, Integer.toString(journey.getElementId()));
                            writer.set(COL_LEG_ID, Integer.toString(i));
                            String boarding = this.transitSchedule.getFacilities().get(trip.getBoardingStop()).getAttributes().getAttribute(STOP_NO).toString();
                            writer.set(COL_FROM_STOP, boarding);
                            String alighting = this.transitSchedule.getFacilities().get(trip.getAlightingStop()).getAttributes().getAttribute(STOP_NO).toString();
                            writer.set(COL_TO_STOP, alighting);

                            Id vId = trip.getVehicleId();
                            PTVehicle vehicle = this.ptVehicles.get(vId);
                            Id<TransitLine> lId = vehicle.getLineId();
                            Id<TransitRoute> rId = vehicle.getRouteId();
                            Attributes routeAttributes = this.transitSchedule.getTransitLines().get(lId).getRoutes().get(rId).getAttributes();
                            String direction = routeAttributes.getAttribute(DIRECTION_CODE).toString();
                            String vsys = routeAttributes.getAttribute(TSYS_CODE).toString();
                            String line = routeAttributes.getAttribute(TRANSITLINE).toString();
                            String lineroute = routeAttributes.getAttribute(LINEROUTENAME).toString();
                            String fzp = routeAttributes.getAttribute(FZPNAME).toString();

                            writer.set(COL_VSYSCODE, vsys);
                            writer.set(COL_LINNAME, line);
                            writer.set(COL_LINROUTENAME, lineroute);
                            writer.set(COL_RICHTUNGSCODE, direction);
                            writer.set(COL_FZPROFILNAME, fzp);

                            String kennung = "E";
                            if (i > 1)
                                kennung = "N";

                            writer.set(COL_TEILWEG_KENNUNG, kennung);
                            writer.set(COL_EINHSTNR, boarding);

                            int time = (int) trip.getPtDepartureTime();

                            writer.set(COL_EINHSTABFAHRTSTAG, getDayIndex(time));
                            writer.set(COL_EINHSTABFAHRTSZEIT, getTime(time));

                            Double pfahrt = 1.0 * scaleFactor;
                            writer.set(COL_PFAHRT, Integer.toString(pfahrt.intValue()));

                            String subpopulation = this.scenario.getPopulation().getPersonAttributes().getAttribute(pax_id,"subpopulation").toString();
                            writer.set(COL_SUBPOP, subpopulation);
                            writer.writeRow();
                            i++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    public String getDayIndex(int time){
        int day = (int) Math.ceil(time / (24 * 60 * 60.0));
        assert day > 0;
        return Integer.toString(day);
    }


    public String getTime(int time){
        int sec = time % (24*60*60);
        return Time.writeTime(sec);
    }

    private class PTVehicle {
        TransitLine transitLine;
        TransitRoute transitRoute;

        private PTVehicle(TransitLine tl, TransitRoute tr) {
            transitLine = tl;
            transitRoute = tr;
        }

        private Id<TransitRoute> getRouteId() {
            return transitRoute.getId();
        }

        private Id<TransitLine> getLineId() {
            return transitLine.getId();
        }
    }
}
