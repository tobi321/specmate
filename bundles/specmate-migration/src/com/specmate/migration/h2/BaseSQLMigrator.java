package com.specmate.migration.h2;

import java.sql.Connection;
import java.util.LinkedList;

import org.eclipse.emf.cdo.common.model.EMFUtil;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EPackage.Registry;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.osgi.service.component.annotations.Component;

import com.specmate.common.SpecmateException;
import com.specmate.migration.api.IMigrator;
import com.specmate.persistency.IPackageProvider;

@Component
public abstract class BaseSQLMigrator implements IMigrator {
	private static final String TABLE_PACKAGE_UNITS = "CDO_PACKAGE_UNITS";
	private static final String TABLE_PACKAGE_INFOS = "CDO_PACKAGE_INFOS";
	private static final String TABLE_EXTERNAL_REFS = "CDO_EXTERNAL_REFS";
	private LinkedList<SQLMigrationStep> migrationSteps;
	private Connection connection;
	protected IPackageProvider packageProvider;
	
	public BaseSQLMigrator() {
		this.migrationSteps = new LinkedList<>();
	}
	
	protected void addMigrationStep(SQLMapper mapper) {
		migrationSteps.addAll(mapper.getMigrationSteps());
	}
	
	protected void executeMigrationQueries(Connection connection) throws SpecmateException {
		this.connection = connection;
		updatePackageUnits();
		SQLUtil.executeStatements(migrationSteps, connection);
	}
	
	private void updatePackageUnits() throws SpecmateException {
		updateExternalRefs();
		writeCurrentPackageUnits();
		removeOldPackageUnits();
	}

	private void updateExternalRefs() throws SpecmateException {
		String failmsg = "Migration: Could not update external references table.";
		
		migrationSteps.addFirst(new SQLMigrationStep("UPDATE " + TABLE_EXTERNAL_REFS + 
				" SET URI=REGEXP_REPLACE(URI,'http://specmate.com/\\d+'," + "'http://specmate.com/" + 
				getTargetVersion() + "')", failmsg));	
	}

	private void removeOldPackageUnits() throws SpecmateException {
		String failmsg = "Migration: Could not delete old package units.";
		
		migrationSteps.addFirst(new SQLMigrationStep("DELETE FROM " + TABLE_PACKAGE_INFOS + 
				" WHERE LEFT(URI,19)='http://specmate.com'", failmsg));
		
		migrationSteps.addFirst(new SQLMigrationStep("DELETE FROM " + TABLE_PACKAGE_UNITS + 
				" WHERE LEFT(ID,19)='http://specmate.com'", failmsg));		
	}

	private void writeCurrentPackageUnits() throws SpecmateException {
		String failmsg = "Exception while writing package units.";
		
		Registry registry = new EPackageRegistryImpl();
		long timestamp = System.currentTimeMillis();
		
		
		for (EPackage pkg : packageProvider.getPackages()) {
			byte[] packageBytes = EMFUtil.getEPackageBytes(pkg, true, registry); 
			StringBuilder sb = new StringBuilder();
			for (byte b : packageBytes) {
				sb.append(String.format("%02X ", b));
			}
			
			SQLMigrationStep stepInfo = new SQLMigrationStep("INSERT INTO " + TABLE_PACKAGE_INFOS + 
					" (URI, UNIT) values (?, ?)", failmsg);
					
			stepInfo.setString(1, pkg.getNsURI(), connection);
			stepInfo.setString(2, pkg.getNsURI(), connection);
			
			SQLMigrationStep stepUnits = new SQLMigrationStep("INSERT INTO " + TABLE_PACKAGE_UNITS + 
					" (ID, ORIGINAL_TYPE, TIME_STAMP, PACKAGE_DATA) values (?, 0, ?, ?)", failmsg);
				
			stepUnits.setString(1, pkg.getNsURI(), connection);
			stepUnits.setLong(2, timestamp, connection);
			stepUnits.setBytes(3, packageBytes, connection);
			
			migrationSteps.addFirst(stepInfo);
			migrationSteps.addFirst(stepUnits);
		}
	}
}
