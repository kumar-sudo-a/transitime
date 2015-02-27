/*
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */

package org.transitime.ipc.data;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.transitime.db.structs.Block;
import org.transitime.db.structs.Route;
import org.transitime.db.structs.ScheduleTime;
import org.transitime.db.structs.Trip;
import org.transitime.utils.MapKey;

/**
 * Used by RMI to transfer data for a schedule for a route/direction/service
 *
 * @author SkiBu Smith
 *
 */
public class IpcSchedule implements Serializable {

	// The members that are to be serialized. They are not declared final
	// because need to use custom serialization/deserialization since don't
	// want to serialize transient members.
	private String routeId;
	private String routeName;
	private String directionId;
	private String directionName;
	private String serviceId;
	private String serviceName;
	private List<IpcScheduleTrip> ipcScheduleTrips;
	
	// Additional members that are transient and not to be serialized
	private final List<Trip> trips;
	
	private static final long serialVersionUID = 1037940637843537012L;

	/********************** Member Functions **************************/

	/**
	 * @param route
	 * @param directionId
	 * @param directionName
	 * @param serviceId
	 * @param serviceName
	 */
	private IpcSchedule(Route route, String directionId, String directionName,
			String serviceId, String serviceName) {
		super();
		this.routeId = route.getId();
		this.routeName = route.getName();
		this.directionId = directionId;
		this.directionName = directionName;
		this.serviceId = serviceId;
		this.serviceName = serviceName;
		
		// Also deal with transient members
		this.trips = new ArrayList<Trip>();
	}

	/**
	 * Use custom serialization so that only the members that are not 
	 * transient are actually serialized.
	 * 
	 * @param oos
	 * @throws IOException
	 */
	private void writeObject(ObjectOutputStream oos) throws IOException {
		oos.writeObject(routeId);
		oos.writeObject(routeName);
		oos.writeObject(directionId);
		oos.writeObject(directionName);
		oos.writeObject(serviceId);
		oos.writeObject(serviceName);
		oos.writeObject(ipcScheduleTrips);
	}
	
	/**
	 * Use custom serialization so that only the members that are not 
	 * transient are actually serialized.
	 * 
	 * @param ois
	 * @throws ClassNotFoundException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	private void readObject(ObjectInputStream ois)
			throws ClassNotFoundException, IOException {
		routeId = (String) ois.readObject();
		routeName = (String) ois.readObject();
		directionId = (String) ois.readObject();
		directionName = (String) ois.readObject();
		serviceId = (String) ois.readObject();
		serviceName = (String) ois.readObject();
		ipcScheduleTrips = (List<IpcScheduleTrip>) ois.readObject();
	}
	
	/**
	 * For keeping track of IpcSchedules in map.
	 */
	private static class ServiceDirectionKey extends MapKey {
		private ServiceDirectionKey(String serviceId, String directionId) {
			super(serviceId, directionId);
		}

		@Override
		public String toString() {
			return "ServiceDirectionKey [" + "serviceId=" + o1
					+ ", directionId=" + o2 + "]";
		}
	}
	  	
	/**
	 * Sorts the trips in order of schedule times. This is a bit difficult
	 * because won't always have times for the same stops for a trip.
	 * Basically need to go through stops for the trips and find the first
	 * common stop and compare times for it.
	 */
	private void sortTrips() {
		// Use special comparator to do the sorting
		Collections.sort(trips, new Comparator<Trip>() {
			@Override
			public int compare(Trip o1, Trip o2) {
				// Find a common stop so can compare times
				int numberStopPaths1 = o1.getNumberStopPaths();
				for (int i1 = 0; i1 < numberStopPaths1; ++i1) {
					ScheduleTime st1 = o1.getScheduleTime(i1);
					String stopId1 = o1.getStopPath(i1).getStopId();
					
					// If found a stop with a schedule time in o1...
					if (st1 != null && st1.getTime() != null) {
						int numberStopPaths2 = o2.getNumberStopPaths();
						for (int i2 = 0; i2 < numberStopPaths2; ++i2) {
							ScheduleTime st2 = o2.getScheduleTime(i2);
							String stopId2 = o2.getStopPath(i2).getStopId();
							
							// If found a schedule time in o2 that corresponds 
							// to one in o1...
							if (st2 != null 
									&& st2.getTime() != null 
									&& stopId1.equals(stopId2)) {
								// Found a match!
								if (st1.getTime() < st2.getTime()) 
									return -1;
								else if (st1.getTime() > st2.getTime())
									return 1;
								else
									return 0;
							}
						}
					}
				}
				// Never found a comparable stop so return 0.
				// This shouldn't actually happen.
				return 0;
			} /* End of compare() method */
			
		});
	}
	
	/**
	 * Finishes configuring the IpcSchedule object by going through all
	 * the trips, sorting them, and creating corresponding list of
	 * IpcScheduleTrips.
	 */
	private void processTrips() {
		// Now that all trips added, sort them
		sortTrips();
				
		// Store the sorted trips as a list of IpcScheduleTrips
		ipcScheduleTrips = new ArrayList<IpcScheduleTrip>();
		for (Trip trip : trips) {
			IpcScheduleTrip ipcScheduleTrip = new IpcScheduleTrip(trip);
			ipcScheduleTrips.add(ipcScheduleTrip);
		}
	}
	
	/**
	 * Creates a IpcSchedule for each service ID and each direction for the
	 * blocks for the route.
	 * 
	 * @param route
	 * @param blocksForRoute
	 * @return
	 */
	public static List<IpcSchedule> createSchedules(Route route,
			List<Block> blocksForRoute) {
		Map<ServiceDirectionKey, IpcSchedule> schedulesMap = 
				new HashMap<ServiceDirectionKey, IpcSchedule>();
		
		// Go through all the blocks and populate the schedulesMap and the
		// tripsMap.
		for (Block block : blocksForRoute) {
			String serviceId = block.getServiceId();
			String serviceName = serviceId;
			
			for (Trip trip : block.getTrips()) {
				// Can have interlining where have different routes for a
				// block. Therefore throw out trips that are not part of block.
				if (!trip.getRouteId().equals(route.getId()))
					continue;
				
				String directionId = trip.getDirectionId();
				String directionName = trip.getHeadsign();
				
				// Determine IpcSchedule for this serviceId/directionId
				ServiceDirectionKey key = 
						new ServiceDirectionKey(serviceId, directionId);
				IpcSchedule ipcSchedule = schedulesMap.get(key);
				if (ipcSchedule == null) {
					ipcSchedule = new IpcSchedule(route, directionId,
							directionName, serviceId, serviceName);
					schedulesMap.put(key, ipcSchedule);
				}
				
				// Add current trip to the IpcSchedule
				ipcSchedule.trips.add(trip);
			}
		}
		
		// Now that all the trips have been processed, finish processing of 
		// each IpcSchedule
		for (IpcSchedule ipcSchedule : schedulesMap.values()) {
			ipcSchedule.processTrips();
		}
		
		// Return the collection of IpcSchedules
		return new ArrayList<IpcSchedule>(schedulesMap.values());
	}
	
	@Override
	public String toString() {
		return "IpcSchedule [" 
					+ "routeId=" + routeId 
					+ ", routeName=" + routeName
					+ ", directionId=" + directionId 
					+ ", directionName=" + directionName 
					+ ", serviceId=" + serviceId 
					+ ", serviceName=" + serviceName 
					+ ", ipcScheduleTrips= + ipcScheduleTrips"
					+ "]";
	}

	public String getRouteId() {
		return routeId;
	}

	public String getRouteName() {
		return routeName;
	}

	public String getDirectionId() {
		return directionId;
	}

	public String getDirectionName() {
		return directionName;
	}

	public String getServiceId() {
		return serviceId;
	}

	public String getServiceName() {
		return serviceName;
	}

	public List<IpcScheduleTrip> getIpcScheduleTrips() {
		return ipcScheduleTrips;
	}
}
