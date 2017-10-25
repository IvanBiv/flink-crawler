package com.scaleunlimited.flinkcrawler.utils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.flink.api.java.tuple.Tuple3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.pojos.BaseUrl;

public class UrlLogger {
	private static final Logger LOGGER = LoggerFactory.getLogger(UrlLogger.class);

	public static long NO_ACTIVITY_TIME = 0;
	
	private static String[] NO_METADATA = new String[0];
	
	private static IUrlLogger URL_LOGGER = loadLogger();
	
	private static AtomicLong LAST_ACTIVITY_TIME = new AtomicLong(NO_ACTIVITY_TIME);
	
	public static void clear() {
		if (URL_LOGGER != null) {
			URL_LOGGER.clear();
		} else {
			throw new IllegalStateException("No URL logging enabled");
		}
	}
	
	public static void record(Class<?> clazz, BaseUrl url) {
		record(clazz, url, NO_METADATA);
	}
	
	public static void record(Class<?> clazz, BaseUrl url, String... metaData) {
		if (LOGGER.isDebugEnabled()) {
			StringBuilder msg = new StringBuilder();
			msg.append(String.format("%s: %s (", clazz.getSimpleName(), url));
			if (metaData.length > 0) {
				msg.append('(');
				for (int i = 0; i < metaData.length; i += 2) {
					if (i > 0) {
						msg.append(", ");
					}
					
					msg.append(metaData[i]);
					msg.append('=');
					msg.append(metaData[i + 1]);
				}
				
				msg.append(')');
			}
			
			LOGGER.debug(msg.toString());
		}
		
		if (URL_LOGGER != null) {
			URL_LOGGER.record(clazz, url, metaData);
		}
		
		long activityTime = System.currentTimeMillis();
		LOGGER.debug("Setting last activity time to " + activityTime);
		LAST_ACTIVITY_TIME.set(activityTime);
	}

	/**
	 * @return time of last URL activity that was logged.
	 */
	public static long getLastActivityTime() {
		return LAST_ACTIVITY_TIME.get();
	}
	
	public static List<Tuple3<Class<?>, String, Map<String, String>>> getLog() {
		if (URL_LOGGER != null) {
			return URL_LOGGER.getLog();
		} else {
			throw new IllegalStateException("No URL logging enabled");
		}
	}
	
	private static IUrlLogger loadLogger() {
		try {
			Class<?> clazz = UrlLogger.class
					.getClassLoader()
					.loadClass("com.scaleunlimited.flinkcrawler.utils.TestUrlLogger");
			return (IUrlLogger)clazz.newInstance();
		} catch (ClassNotFoundException e) {
			return null;
		} catch (IllegalAccessException | InstantiationException e) {
			throw new RuntimeException("Can't create URL logger", e);
		}
	}
	
}
