package com.specmate.migration.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.specmate.common.SpecmateException;
import com.specmate.migration.test.attributeadded.testmodel.base.BasePackage;
import com.specmate.migration.test.support.TestModelProviderImpl;

public class ConsistentMigrationTest extends MigrationTestBase {

	public ConsistentMigrationTest() throws Exception {
		super("consistentmigrationtest", BasePackage.class.getName());
	}
	
	@Override
	@Test
	public void doMigration() throws Exception {
		checkMigrationPreconditions();
				
		assertFalse(migratorService.needsMigration());
		
		TestModelProviderImpl testModel = (TestModelProviderImpl) getTestModelService();
		testModel.setModelName(testModelName);
		
		assertTrue(migratorService.needsMigration());
		
		// Initiate the migration
		persistency.shutdown();
		try {
			persistency.start();
		} catch (SpecmateException e) {
			assertNotNull(e.getMessage());
			testModel.setModelName(com.specmate.migration.test.baseline.testmodel.base.BasePackage.class.getName());
			persistency.start();
		}
		
		checkMigrationPostconditions();
		
		// Resetting the model to the base model such that all tests start with the same model
		testModel.setModelName(BasePackage.class.getName());
	}
	
	@Override
	protected void checkMigrationPostconditions() throws Exception {
		
	}

}
