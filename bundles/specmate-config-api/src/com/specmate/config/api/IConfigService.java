package com.specmate.config.api;

import java.util.Map.Entry;
import java.util.Set;

public interface IConfigService {
	/** Retreives a configured property. Returns null if property is not found. */
	public String getConfigurationProperty(String key);

	/**
	 * Retreives a configured property. Returns the default value if no entry is
	 * found in the configuration.
	 */
	public String getConfigurationProperty(String key, String defaultValue);

	public Integer getConfigurationPropertyInt(String key);

	public Integer getConfigurationPropertyInt(String key, int defaultValue);

	Set<Entry<Object, Object>> getConfigurationProperties(String prefix);

	String[] getConfigurationPropertyArray(String key);
}
