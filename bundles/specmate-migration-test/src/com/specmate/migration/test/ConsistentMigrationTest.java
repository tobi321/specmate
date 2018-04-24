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
			// At this point, if the rollback works, we the database would be at model version 0 and the target model
			// is the baseline, i.e. no migration is necessary. However, rollback does not work for schema changing 
			// operations in H2. Hence, migration is attempted and failed, as there is no migrator for model version 1.
			// This is expected behavior that we currently cannot fix.
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
