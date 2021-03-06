/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.analysis.travelcomponents;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.pt.router.TransitRouterConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class TravelComponent {

	public static double walkSpeed = new TransitRouterConfig(ConfigUtils.createConfig()).getBeelineWalkSpeed();
	public double getDuration() {
		return getEndTime() - getStartTime();
	}

	private static AtomicInteger id = new AtomicInteger(0); // for enumeration

	private double startTime;
	private double endTime = 30 * 3600;

	private int elementId;

	public TravelComponent(Config config) {
		elementId = id.incrementAndGet();
		endTime = config.qsim().getEndTime();
	}

	public int getElementId() {
		return elementId;
	}

	public double getStartTime() {
		return startTime;
	}

	public void setStartTime(double startTime) {
		this.startTime = startTime;
	}

	public double getEndTime() {
		return endTime;
	}

	public void setEndTime(double endTime) {
		this.endTime = endTime;
	}


	





	

	 


}
