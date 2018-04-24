package com.specmate.migration.h2;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.specmate.common.SpecmateException;

public abstract class SQLMapper {
	protected static final String SPECMATE_URL = "http://specmate.com/"; 
	protected Connection connection;
	protected String packageName;
	protected String sourceVersion;
	protected String targetVersion;
	protected List<SQLMigrationStep> migrationSteps;
	protected static int externalReferenceId = 0;
	
	public SQLMapper(Connection connection, String packageName, String sourceVersion, String targetVersion) {
		this.connection = connection;
		this.packageName = packageName;
		this.sourceVersion = sourceVersion;
		this.targetVersion = targetVersion;
		this.migrationSteps = new ArrayList<>();
	}
	
	public List<SQLMigrationStep> getMigrationSteps() {
		return this.migrationSteps;
	}

	protected void insertExternalReferences(String objectName, List<String> attributeNames) throws SpecmateException {
		if (externalReferenceId == 0) {
			externalReferenceId = SQLUtil.getIntResult("SELECT id FROM CDO_EXTERNAL_REFS ORDER BY id ASC LIMIT 1", 1, connection);
		}
		
		String baseUri = SPECMATE_URL + targetVersion + "/" + packageName + "#//" + objectName;
		externalReferenceId = externalReferenceId - 1;
		migrationSteps.add(getInsertExternalReferenceQuery(baseUri, externalReferenceId));
		for (String name : attributeNames) {
			String attributeUri = baseUri + "/" + name;
			externalReferenceId = externalReferenceId - 1;
			migrationSteps.add(getInsertExternalReferenceQuery(attributeUri, externalReferenceId));
		}
	}
	
	protected void renameExternalReference(String objectName, String oldAttributeName, String newAttributeName) 
			throws SpecmateException {
		String baseUri = SPECMATE_URL + targetVersion + "/" + packageName + "#//" + objectName + "/";
		String oldUri = baseUri + oldAttributeName;
		String newUri = baseUri + newAttributeName;
		migrationSteps.add(new SQLMigrationStep("UPDATE CDO_EXTERNAL_REFS SET uri = '" + newUri + "' WHERE uri = '" + 
				oldUri + "'"));
	}
	
	private SQLMigrationStep getInsertExternalReferenceQuery(String uri, int id) throws SpecmateException {
		Date now = new Date();
		return new SQLMigrationStep("INSERT INTO CDO_EXTERNAL_REFS (ID, URI, COMMITTIME) " +
				"VALUES (" + id + ", '" + uri + "', " + now.getTime() + ")");
	}
}
