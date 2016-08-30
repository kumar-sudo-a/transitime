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
package org.transitime.applications;

import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.time.DateUtils;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.config.ConfigFileReader;
import org.transitime.configData.AgencyConfig;
import org.transitime.configData.CoreConfig;
import org.transitime.core.ServiceUtils;
import org.transitime.core.TimeoutHandlerModule;
import org.transitime.core.dataCache.PredictionDataCache;
import org.transitime.core.dataCache.StopArrivalDepartureCache;
import org.transitime.core.dataCache.TripDataHistoryCache;
import org.transitime.core.dataCache.VehicleDataCache;
import org.transitime.db.hibernate.DataDbLogger;
import org.transitime.db.hibernate.HibernateUtils;
import org.transitime.db.structs.ActiveRevisions;
import org.transitime.db.structs.Agency;
import org.transitime.gtfs.DbConfig;
import org.transitime.ipc.servers.CacheQueryServer;
import org.transitime.ipc.servers.CommandsServer;
import org.transitime.ipc.servers.ConfigServer;
import org.transitime.ipc.servers.PredictionAnalysisServer;
import org.transitime.ipc.servers.PredictionsServer;
import org.transitime.ipc.servers.ServerStatusServer;
import org.transitime.ipc.servers.VehiclesServer;
import org.transitime.modules.Module;
import org.transitime.monitoring.PidFile;
import org.transitime.utils.SettableSystemTime;
import org.transitime.utils.SystemTime;
import org.transitime.utils.SystemCurrentTime;
import org.transitime.utils.Time;

/**
 * The main class for running a Transitime Core real-time data processing
 * system. Handles command line arguments and then initiates AVL feed.
 * 
 * @author SkiBu Smith
 * 
 */
public class Core {
	
	private static Core singleton = null;
	
	// Contains the configuration data read from database
	private final DbConfig configData;
	
	// For logging data such as AVL reports and arrival times to database
	private final DataDbLogger dataDbLogger;

	private final TimeoutHandlerModule timeoutHandlerModule;
	
	private final ServiceUtils service;
	private final Time time;

	// So that can access the current time, even when in playback mode
	private SystemTime systemTime = new SystemCurrentTime();
	
	// Set by command line option. Specifies config rev to use if set
	private static String configRevStr = null;

	// Read in configuration files. This should be done statically before
	// the logback LoggerFactory.getLogger() is called so that logback can
	// also be configured using a transitime config file. The files are
	// specified using the java system property -Dtransitime.configFiles .
	static {
		ConfigFileReader.processConfig();
	}
	
	private static final Logger logger = 
			LoggerFactory.getLogger(Core.class);
	
	/********************** Member Functions **************************/

	/**
	 * Construct the Core object and read in the config data. This is private
	 * so that the createCore() factory method must be used.
	 * 
	 * @param agencyId
	 */
	private Core(String agencyId) {
		// Determine configuration rev to use. If one specified on command
		// line, use it. If not, then use revision stored in db.
		int configRev;
		if (configRevStr != null) {
			// Use config rev from command line
			configRev = Integer.parseInt(configRevStr);
		} else {
			// Read in config rev from ActiveRevisions table in db
			ActiveRevisions activeRevisions = ActiveRevisions.get(agencyId);
			
			// If config rev not set properly then can't do anything so exit
			if (!activeRevisions.isValid()) {
				logger.error("ActiveRevisions in database is not valid. The "
						+ "configuration revs must be set to proper values. {}", 
						activeRevisions);
				System.exit(-1);
			}
			configRev = activeRevisions.getConfigRev();
		}

		// Set the timezone so that when dates are read from db or are logged 
		// the time will be correct. Therefore this needs to be done right at
		// the start of the application, before db is read.
		TimeZone timeZone = Agency.getTimeZoneFromDb(agencyId);
		TimeZone.setDefault(timeZone);
		
		// Clears out the session factory so that a new one will be created for
		// future db access. This way new db connections are made. This is
		// useful for dealing with timezones and postgres. For that situation
		// want to be able to read in timezone from db so can set default 
		// timezone. Problem with postgres is that once a factory is used to 
		// generate sessions the database will continue to use the default 
		// timezone that was configured at that time. This means that future 
		// calls to the db will use the wrong timezone! Through this function 
		// one can read in timezone from database, set the default timezone, 
		// clear the factory so that future db connections will use the newly 
		// configured timezone, and then successfully process dates.
		HibernateUtils.clearSessionFactory();
		
		// Read in all GTFS based config data from the database
		configData = new DbConfig(agencyId);
		configData.read(configRev);
		
		// Create the DataDBLogger so that generated data can be stored
		// to database via a robust queue. But don't actually log data
		// if in playback mode since then would be writing data again 
		// that was first written when predictor was run in real time.
		// Note: DataDbLogger needs to be started after the timezone is set.
		// Otherwise when running for a different timezone than what the
		// computer is setup for then can log data using the wrong time!
		// This is strange since setting TimeZone.setDefault() is supposed
		// to work across all threads it appears that sometimes it wouldn't
		// work if Db logger started first.
		dataDbLogger = DataDbLogger.getDataDbLogger(agencyId,
				CoreConfig.storeDataInDatabase(),
				CoreConfig.pauseIfDbQueueFilling());
		
		// Start mandatory modules
		timeoutHandlerModule = new TimeoutHandlerModule(AgencyConfig.getAgencyId());
		timeoutHandlerModule.start();
		
		service = new ServiceUtils(configData);
		time = new Time(configData);
	}
	
	/**
	 * Creates the Core object for the application. There can only be one Core
	 * object per application. Uses CoreConfig.getAgencyId() to determine the
	 * agencyId. This means it typically uses the agency ID specified by the
	 * Java property -Dtransitime.core.agencyId .
	 * <p>
	 * Usually doesn't need to be called directly because can simply use
	 * Core.getInstance().
	 * <p>
	 * Synchronized to ensure that don't create more than a single Core.
	 * 
	 * @return The Core singleton, or null if could not create it
	 */
	synchronized public static Core createCore() {
		String agencyId = AgencyConfig.getAgencyId();

		// If agencyId not set then can't create a Core. This can happen
		// when doing testing.
		if (agencyId == null) {
			logger.error("No agencyId specified for when creating Core.");
			return null;
		}
		
		// Make sure only can have a single Core object
		if (Core.singleton != null) {
			logger.error("Core singleton already created. Cannot create another one.");
			return null;
		}
		
		Core core = new Core(agencyId);
		Core.singleton = core;
		return core;
	}
	
	/**
	 * For obtaining singleton Core object
	 * 
	 * @returns the Core singleton object for this application, or null if it
	 *          could not be created
	 */
	public static Core getInstance() {
		if (Core.singleton == null)
			createCore();
		
		return singleton;
	}
	
	/**
	 * Returns true if core application. If GTFS processing or other application
	 * then not a Core application and should't try to read in data such as
	 * route names for a trip.
	 * 
	 * @return true if core application
	 */
	public static boolean isCoreApplication() {
		return Core.singleton != null;
	}
	
	/**
	 * Makes the config data available to all
	 * @return
	 */
	public DbConfig getDbConfig() {
		return configData;
	}
	
	/**
	 * Returns the ServiceUtils object that can be reused for efficiency.
	 * @return
	 */
	public ServiceUtils getServiceUtils() {
		return service;
	}
	
	/**
	 * For when want to use methods in Time. This is important when need
	 * methods that access a Calendar a lot. By putting the Calendar in
	 * Time it can be shared.
	 * @return
	 */
	public Time getTime() {
		return time;
	}
	
	/**
	 * For when need system time but might be in playback mode. If not in
	 * playback mode then the time will be the time of the system clock. But if
	 * in playback mode then will be using a SettableSystemTime and the time
	 * will be that of the last AVL report.
	 * 
	 * @return The system epoch time
	 */
	public long getSystemTime() {
		return systemTime.get();
	}
	
	/**
	 * For when need system time but might be in playback mode. If not in
	 * playback mode then the time will be the time of the system clock. But if
	 * in playback mode then will be using a SettableSystemTime and the time
	 * will be that of the last AVL report.
	 * 
	 * @return The system epoch time
	 */
	public Date getSystemDate() {
		return new Date(getSystemTime());
	}
	
	/**
	 * For setting the system time when in playback or batch mode.
	 * 
	 * @param systemTime
	 */
	public void setSystemTime(long systemEpochTime) {
		this.systemTime = new SettableSystemTime(systemEpochTime);
	}
	
	/**
	 * Returns the Core logger so that each class doesn't need to create
	 * its own and have it be configured properly.
	 * @return
	 */
	public static final Logger getLogger() {
		return logger;
	}
	
	/**
	 * This method logs status of the logger system to the console. Could 
	 * be useful for seeing if there are problems with the logger config file.
	 */
	private static void outputLoggerStatus() {
		// For debugging output current state of logger
// Commented out for now because not truly useful
//	    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
//	    StatusPrinter.print(lc);		
	}
	
	/**
	 * Returns the DataDbLogger for logging data to db.
	 * @return
	 */
	public DataDbLogger getDbLogger() {
		return dataDbLogger;
	}
	
	/**
	 * Returns the timeout handler module
	 * @return
	 */
	public TimeoutHandlerModule getTimeoutHandlerModule() {
		return timeoutHandlerModule;
	}
	
	/**
	 * Processes all command line options using Apache CLI.
	 * Further info at http://commons.apache.org/proper/commons-cli/usage.html
	 */
	@SuppressWarnings("static-access")  // Needed for using OptionBuilder
	private static void processCommandLineOptions(String[] args) 
			throws ParseException {
		// Specify the options
		Options options = new Options();
		options.addOption("h", "help", false, "Display usage and help info."); 
				
		options.addOption(OptionBuilder.withArgName("configRev")
                .hasArg()
                .withDescription("Specifies optional configuration revision. "
                		+ "If not set then the configuration rev will be read "
                		+ "from the ActiveRevisions table in the database.")
                .create("configRev")
                );
		
		// Parse the options
		CommandLineParser parser = new BasicParser();
		CommandLine cmd = parser.parse( options, args);
		
		// Handle optional config rev
		if (cmd.hasOption("configRev")) {
			configRevStr = cmd.getOptionValue("configRev");
		}

		// Handle help option
		if (cmd.hasOption("h")) {
			// Display help
			final String commandLineSyntax = "java transitime.jar";
			final PrintWriter writer = new PrintWriter(System.out);
			final HelpFormatter helpFormatter = new HelpFormatter();
			helpFormatter.printHelp(writer,
									80, // printedRowWidth
									commandLineSyntax,
									"args:", // header
									options,
									2,             // spacesBeforeOption
									2,             // spacesBeforeOptionDescription
									null,          // footer
									true);         // displayUsage
			writer.close();
			System.exit(0);
		}
	}
	
	/**
	 * Start the RMI Servers so that clients can obtain data
	 * on predictions, vehicles locations, etc.
	 *  
	 * @param agencyId
	 */
	private static void startRmiServers(String agencyId) {
		// Start up all of the RMI servers
		PredictionsServer.start(agencyId, PredictionDataCache.getInstance());
		VehiclesServer.start(agencyId, VehicleDataCache.getInstance());
		ConfigServer.start(agencyId);
		ServerStatusServer.start(agencyId);
		CommandsServer.start(agencyId);
		CacheQueryServer.start(agencyId);
		PredictionAnalysisServer.start(agencyId);
		
	}
	
	/**
	 * The main program that runs the entire Transitime application.!
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			try {
				processCommandLineOptions(args);
			} catch (ParseException e1) {
				e1.printStackTrace();
				System.exit(-1);
			}
			
			// Write pid file so that monit can automatically start
			// or restart this application
			PidFile.createPidFile(CoreConfig.getPidFileDirectory()
					+ AgencyConfig.getAgencyId() + ".pid");
			
			// For making sure logger configured properly
			outputLoggerStatus();
			
			// Initialize the core now
			createCore();
			
			Session session = HibernateUtils.getSession();
			
			Date endDate=Calendar.getInstance().getTime();
			/* populate one day at a time to avoid memory issue */
			for(int i=0;i<CoreConfig.getDaysPopulateHistoricalCache();i++)
			{
				Date startDate=DateUtils.addDays(endDate, -1);
				
				logger.debug("Populating TripDataHistoryCache cache for period {} to {}",startDate,endDate);
				TripDataHistoryCache.getInstance().populateCacheFromDb(session, startDate, endDate);
				
				endDate=startDate;
			}
						
			endDate=Calendar.getInstance().getTime();
			/* populate one day at a time to avoid memory issue */
			for(int i=0;i<CoreConfig.getDaysPopulateHistoricalCache();i++)
			{
				Date startDate=DateUtils.addDays(endDate, -1);
				
				logger.debug("Populating StopArrivalDepartureCache cache for period {} to {}",startDate,endDate);
				StopArrivalDepartureCache.getInstance().populateCacheFromDb(session, startDate, endDate);
				
				endDate=startDate;
			}
			
			// Start any optional modules. 
			List<String> optionalModuleNames = CoreConfig.getOptionalModules();
			if (optionalModuleNames.size() > 0)
				logger.info("Starting up optional modules specified via " + 
						"transitime.modules.optionalModulesList param:");
			else
				logger.info("No optional modules to start up.");
			for (String moduleName : optionalModuleNames) {
				logger.info("Starting up optional module " + moduleName);
				Module.start(moduleName);
			}	
			
			// Start the RMI Servers so that clients can obtain data
			// on predictions, vehicles locations, etc.
			String agencyId = AgencyConfig.getAgencyId();			
			startRmiServers(agencyId);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			e.printStackTrace();
		}
	}

}
