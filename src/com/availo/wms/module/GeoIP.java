package com.availo.wms.module;

import com.wowza.wms.amf.*;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.client.*;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.rtp.model.RTPSession;

import com.maxmind.geoip.*;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The GeoIP class adds GeoIP functionality to WowzaMediaServer applications that require this.
 * @author Brynjar Eide <brynjar@availo.no>
 * @version 3.0, 2012-11-15 
 */
public class GeoIP extends ModuleBase {

	/**
	 * Keep the LookupService object cached in memory.
	 * 
	 * "You should only call LookupService once, especially if you use
	 * GEOIP_MEMORY_CACHE mode, since the LookupService constructor takes up
	 * resources to load the GeoIP.dat file into memory."
	 */
	private static LookupService cl;

	/**
	 * Keep track of when the GeoIP database was last read.
	 * Allows reloading it on certain intervals.
	 */
	private static long databaseLastModified = 0L;
	
	/**
	 * Keep track of when the exceptions-file was last read.
	 * Allows reloading it after the file has been modified.
	 */
	private long exceptionsLastModified = 0L;
	
	/**
	 * Whether to permit all connections by default. Can be defined in Application.conf
	 */
	private boolean defaultPermit = false;
	
	/**
	 * An optional default country that should be allowed if nothing is defined in Application.xml
	 */
	private String defaultAllowCountry = "";
	
	/**
	 * Application path
	 */
	private String applicationPath;
	
	/**
	 * Where to find the GeoIP.dat file. Can be overriden in Application.conf
	 */
	private String geoipDatabasePath = "/usr/local/share/GeoIP/GeoIP.dat";
	
	/**
	 * Map that contains countries that override the default behaviour
	 * true = has access
	 * false = does not have access
	 */
	private Map<String, Boolean> countryCodeAccessList;
	
	/**
	 * A list of all exceptions
	 */
	private List<String> exceptions;
	
	public void onAppStart(IApplicationInstance appInstance) {
		this.defaultPermit =  appInstance.getProperties().getPropertyBoolean("geoipDefaultPermit", this.defaultPermit);
		this.geoipDatabasePath = appInstance.getProperties().getPropertyStr("geoipDatabasePath", this.geoipDatabasePath);
		String allowCountries = appInstance.getProperties().getPropertyStr("geoipAllowCountries", this.defaultAllowCountry);
		String denyCountries = appInstance.getProperties().getPropertyStr("geoipDenyCountries", null);
		
		this.countryCodeAccessList = new HashMap<String, Boolean>();

		if (allowCountries != null) {
			if (allowCountries.indexOf("|") >= 0) {
				String[] allowCountriesArray = allowCountries.split(Pattern.quote("|"));
				for (String country : allowCountriesArray) {
					if (country != null) {
						this.countryCodeAccessList.put(country, true);
					}
				}
			}
			else {
				this.countryCodeAccessList.put(allowCountries, true);
			}
		}

		if (denyCountries != null) {
			if (denyCountries.indexOf("|") >= 0) {
				String[] denyCountriesArray = denyCountries.split(Pattern.quote("|"));
				for (String country : denyCountriesArray) {
					if (country != null) {
						this.countryCodeAccessList.put(country, false);
					}
				}
			}
			else {
				this.countryCodeAccessList.put(denyCountries, false);
			}
		}
		
		
		File appConfigFile = new File(appInstance.getApplication().getConfigPath());
		this.applicationPath = appConfigFile.getParent();
		this.exceptions = new ArrayList<String>();
		this.refreshDatabase();
		this.refreshExceptions();
	}
	
	/**
	 * Load (or reload) the GeoIP database
	 */
	private void refreshDatabase() {
		try {
			File checkDbTime = new File(this.geoipDatabasePath);

			// cl is null if this is the first time we accept a connection.
			if (cl == null) { 
				WMSLoggerFactory.getLogger(GeoIP.class).info("Loading GeoIP-cache '" + this.geoipDatabasePath + "'...", "GeoIP", "comment");
				databaseLastModified = checkDbTime.lastModified();
				cl = new LookupService(this.geoipDatabasePath,LookupService.GEOIP_MEMORY_CACHE);
			}

			// Let's refresh our cache once every 24 hours as well, in case the database file has been updated
			else if (databaseLastModified != checkDbTime.lastModified()) { 
				databaseLastModified = checkDbTime.lastModified();
				WMSLoggerFactory.getLogger(GeoIP.class).info("GeoIP-file '" + this.geoipDatabasePath + "'has changed. Reloading GeoIP-cache...", "GeoIP", "comment");
				databaseLastModified = System.currentTimeMillis();
				cl.close();
				cl = new LookupService(this.geoipDatabasePath,LookupService.GEOIP_MEMORY_CACHE);
			}
		}
		catch (IOException e) {
			WMSLoggerFactory.getLogger(GeoIP.class).error("IO Exception! (Missing GeoIP-database file?)", "GeoIP", "comment");
		}
	}
	
	/**
	 * Load (or reload) the exceptions specific to this application
	 */
	private void refreshExceptions() {
		try {
			String exceptionsfile = this.applicationPath + java.io.File.separator + "geoip.exceptions";
			
			File checkTime = new File(exceptionsfile);
			if (this.exceptionsLastModified == checkTime.lastModified()) {
				return;
			}
			
			if (checkTime.exists()) {
				if (this.exceptionsLastModified == 0L) {
					WMSLoggerFactory.getLogger(GeoIP.class).info("Loading exceptions-file '" + exceptionsfile + "'.", "GeoIP", "comment");
				}
				else {
					WMSLoggerFactory.getLogger(GeoIP.class).info("Exceptionsfile '" + exceptionsfile + "' has changed. Refreshing exceptions.", "GeoIP", "comment");
				}
				this.exceptionsLastModified = checkTime.lastModified();
				
				// Open the file that is the first command line parameter
				FileInputStream fstream = new FileInputStream(exceptionsfile);
				// Get the object of DataInputStream
				DataInputStream in = new DataInputStream(fstream);
				BufferedReader br = new BufferedReader(new InputStreamReader(in));
				String strLine;
	
				this.exceptions = new ArrayList<String>();
				//Read File Line By Line
				while ((strLine = br.readLine()) != null) {
					if (strLine.indexOf("#") != 0 && strLine.indexOf("//") != 0 && strLine.indexOf(";") != 0) {
						this.exceptions.add(strLine);
					}
				}
				//Close the input stream
				in.close();
			}
			else {
				// File has been removed at some point. Reset the exception list.
				WMSLoggerFactory.getLogger(GeoIP.class).info("Exceptionsfile '" + exceptionsfile + "' has been removed. Removing all exceptions.", "GeoIP", "comment");
				this.exceptions = new ArrayList<String>();
			}
		}
		catch (IOException e) {
			WMSLoggerFactory.getLogger(GeoIP.class).warn("IO Exception: " + e.getMessage(), "GeoIP", "comment");
		}
		
	}
	
	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		this.refreshDatabase();
		this.refreshExceptions();

		String clientIpAddress = client.getIp();
		if (!this.checkAddress(clientIpAddress)) {
			client.call("geoIPRestriction");
			client.rejectConnection();
		}
	}
	
	public void onHTTPSessionCreate(IHTTPStreamerSession httpSession) {
		this.refreshDatabase();
		this.refreshExceptions();
		
		String clientIpAddress = httpSession.getIpAddress();
		if (!this.checkAddress(clientIpAddress)) {
			httpSession.rejectSession();
		}		
	}
	
	public void onRTPSessionCreate(RTPSession rtpSession) {
		this.refreshDatabase();
		this.refreshExceptions();
		
		String clientIpAddress = rtpSession.getIp();
		if (!this.checkAddress(clientIpAddress)) {
			rtpSession.rejectSession();
		}		
	}
	
	/**
	 * Check exceptions-file inside application directory and Application.xml for a match on clientIpAddress
	 * @param clientIpAddress
	 * @return true if accepted, false if rejected.
	 */
	private boolean checkAddress(String clientIpAddress) {
		Country clientCountry = cl.getCountry(clientIpAddress);

		// We need to check the most specific first. Start with the exceptions.
		for (String strLine : this.exceptions) {
			// Check for exact match
			if (clientIpAddress.equals(strLine)) {
				WMSLoggerFactory.getLogger(GeoIP.class).info("Found ip-address: " + clientIpAddress + " (Country: " + clientCountry.getName() + ") in exceptions-file as '" + strLine + "'. Accepting.", "GeoIP", "comment");
				return true;
			}
			// Check for match inside a subnet
			if (strLine.indexOf("/") > 0 && isInRange(strLine, clientIpAddress)) {
				WMSLoggerFactory.getLogger(GeoIP.class).info("Found ip-address: " + clientIpAddress + " (Country: " + clientCountry.getName() + ") in exceptions-file inside the subnet '" + strLine + "'. Accepting.", "GeoIP", "comment");
				return true;
			}
			// Check for country match. This can create a lot of spam if all countries are placed in the exceptions file. Use Application.conf to avoid spam.
			if (clientCountry.getCode().equals(strLine) || clientCountry.getName().equals(strLine)) {
				WMSLoggerFactory.getLogger(GeoIP.class).info("IP address: " + clientIpAddress + " (Country: " + clientCountry.getName() + ") allowed in exceptions-file as '" + strLine + "'. Accepting.", "GeoIP", "comment");
				return true;
			}
		}
		
		// Then we check the application config. Format: NO|Denmark|SE|UK|Finland
		for (Map.Entry<String, Boolean> entry : this.countryCodeAccessList.entrySet()) {
			WMSLoggerFactory.getLogger(GeoIP.class).debug("Testing '" + clientCountry.getCode() + "' and '" + clientCountry.getName() + "' against '" + entry.getKey() + "'.", "GeoIP", "comment");
			if (clientCountry.getCode().equals(entry.getKey()) || clientCountry.getName().equals(entry.getKey())) {
				if (entry.getValue() == true) {
					// This is the expected case, so we'll keep this as a debug line to avoid spamming the logs on production servers.
					WMSLoggerFactory.getLogger(GeoIP.class).debug("IP-address " + clientIpAddress + " (Country: " + clientCountry.getName() + ") is allowed globally as '" + entry.getKey() + "'. Accepting.", "GeoIP", "comment");
					return true;
				}
				else if (entry.getValue() == false) {
					WMSLoggerFactory.getLogger(GeoIP.class).warn("IP-address " + clientIpAddress + " (Country: " + clientCountry.getName() + ") is denied globally as '" + entry.getKey() + "', and no exception was found. Rejecting.", "GeoIP", "comment");
					return false;
				}
			}
		}

		// And finally, if we're still here, we'll use the default action.
		if (this.defaultPermit) {
			// This will possibly lead to spam again.
			WMSLoggerFactory.getLogger(GeoIP.class).debug("No match for " + clientIpAddress + " (Country: " + clientCountry.getName() + ") in exceptions or Application.xml. Accepting as a default action.", "GeoIP", "comment");
			return true;
		}

		WMSLoggerFactory.getLogger(GeoIP.class).warn("No match for " + clientIpAddress + " (Country: " + clientCountry.getName() + ") in exceptions or Application.xml. Rejecting as a default action.", "GeoIP", "comment");
		return false;

	}
	
	/**
	 * Check if an IP address is in a particular subnet
	 * @author Charles Johnson
	 * 
	 * Borrowed from user CEHJ (http://www.experts-exchange.com/M_20998.html) in the following post:
	 * http://www.experts-exchange.com/Programming/Languages/Java/Q_22546384.html#a19017697
	 * 
	 * @param targetIpCidr
	 * @param testIp
	 * @return
	 */
	private static boolean isInRange(String targetIpCidr, String testIp) {
		String[] atoms = targetIpCidr.split("/");
		int cidrMask = Integer.parseInt(atoms[1]);
		long target = ipToLong(atoms[0]);
		long test = ipToLong(testIp);
		int tempMask = (2 << (31 - cidrMask)) - 1;
		if (cidrMask == 32) {
			return (target == test);
		}
		return (target | tempMask) == (test | tempMask);
	}



	/**
	 * Convert an IP address to its numeric value
	 * @author Charles Johnson
	 * 
	 * Borrowed from user CEHJ (http://www.experts-exchange.com/M_20998.html) in the following post:
	 * http://www.experts-exchange.com/Programming/Languages/Java/Q_22546384.html#a19017697

	 * @param ipAddress
	 * @return
	 */
	private static long ipToLong(String ipAddress) {
		long result = 0;
		try {
			byte[] bytes = InetAddress.getByName(ipAddress).getAddress();
			long octet1 = bytes[0] & 0xFF;
			octet1 <<= 24;
			long octet2 = bytes[1] & 0xFF;
			octet2 <<= 16;
			long octet3 = bytes[2] & 0xFF;
			octet3 <<= 8;
			long octet4 = bytes[3] & 0xFF;
			result = octet1 | octet2 | octet3 | octet4;
		} catch(Exception e) {
			e.printStackTrace();
			return 0;
		}
		return result;
	}
	
}