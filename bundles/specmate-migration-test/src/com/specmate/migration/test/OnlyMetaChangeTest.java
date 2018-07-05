package com.specmate.migration.test;

import com.specmate.migration.test.onlymetachange.testmodel.base.BasePackage;

public class OnlyMetaChangeTest extends MigrationTestBase {

	public OnlyMetaChangeTest() throws Exception {
		super("onlymetachangetest", BasePackage.class.getName());
	}

	@Override
	protected void checkMigrationPostconditions() throws Exception {
		// nothing to check
	}

}
