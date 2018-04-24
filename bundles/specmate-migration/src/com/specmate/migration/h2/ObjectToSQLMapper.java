package com.specmate.migration.h2;

import java.sql.Connection;
import java.util.List;

import com.specmate.common.SpecmateException;

public class ObjectToSQLMapper extends SQLMapper {

	public ObjectToSQLMapper(Connection connection, String packageName, String sourceVersion, String targetVersion) {
		super(connection, packageName, sourceVersion, targetVersion);
	}
	
	public void newObject(String tableName, List<String> attributeNames) throws SpecmateException {
		String failmsg = "Migration: Could not add table " + tableName + ".";
		
		migrationSteps.add(new SQLMigrationStep("CREATE TABLE " + tableName + "(" +
				"CDO_ID BIGINT NOT NULL, " +
				"CDO_VERSION INTEGER NOT NULL, " +
				"CDO_CREATED BIGINT NOT NULL, " +
				"CDO_REVISED BIGINT NOT NULL, " +
				"CDO_RESOURCE BIGINT NOT NULL, " +
				"CDO_CONTAINER BIGINT NOT NULL, " +
				"CDO_FEATURE INTEGER NOT NULL)", failmsg));
		
		migrationSteps.add(new SQLMigrationStep("CREATE UNIQUE INDEX " + 
				SQLUtil.createRandomIdentifier("PRIMARY_KEY_" + tableName) + 
				" ON " + tableName + " (CDO_ID ASC, CDO_VERSION ASC)", failmsg));
		
		migrationSteps.add(new SQLMigrationStep("CREATE INDEX " + 
				SQLUtil.createRandomIdentifier("INDEX_" + tableName) 
				+ " ON " + tableName + " (CDO_REVISED ASC)", failmsg));
		
		migrationSteps.add(new SQLMigrationStep("ALTER TABLE " + tableName + " ADD CONSTRAINT " + 
				SQLUtil.createRandomIdentifier("CONSTRAINT_" + tableName) + 
				" PRIMARY KEY (CDO_ID, CDO_VERSION)", failmsg));
		
		insertExternalReferences(tableName, attributeNames);
	}
}
