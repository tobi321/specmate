package com.specmate.jettystarter.internal;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.server.Server;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.log.LogService;

import com.specmate.config.api.IConfigService;

@Component(immediate=true)
public class SpecmateServer {
	
	private IConfigService configService;
	private LogService logService;

	@Activate
	public void activate(BundleContext bundleContext) {
		String httpPortConfig = configService.getConfigurationProperty("http.port");
		int httpPort = 8080;
		if(httpPortConfig!=null) {
			try {
				httpPort = Integer.parseInt(httpPortConfig);
			} catch(Exception e) {
				logService.log(LogService.LOG_ERROR, "Invalid value for http.port: " + httpPortConfig);
			}
		}
		logService.log(LogService.LOG_INFO,"Configuring jetty with port " + httpPort);
		Server server = new Server(httpPort);
		Dictionary<String, String> properties = new Hashtable<>();
		properties.put("jetty.home.bundle","specmate-jettystarter");
		properties.put("jetty.etc.config.urls","etc/jetty.xml,etc/jetty-http.xml,etc/jetty-deployer.xml,etc/jetty-rewrite.xml");
		properties.put("managedServerName","defaultJettyServer");
		

		ServiceRegistration<?> sr = bundleContext.registerService(Server.class.getName(), server, properties);
		
	}
	
	@Reference
	public void setConfigService(IConfigService configServce) {
		this.configService = configServce;
	}
	
	@Reference
	public void setLogService(LogService logService) {
		this.logService=logService;
	}
	
	
}
