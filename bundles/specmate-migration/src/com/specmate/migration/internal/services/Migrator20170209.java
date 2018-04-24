package com.specmate.migration.internal.services;

import java.sql.Connection;

import org.osgi.service.component.annotations.Component;

import com.specmate.common.SpecmateException;
import com.specmate.migration.api.IMigrator;
import com.specmate.migration.h2.BaseSQLMigrator;

@Component(service = IMigrator.class, property = "sourceVersion=20170209")
public class Migrator20170209 extends BaseSQLMigrator {

	@Override
	public String getSourceVersion() {
		return "20170209";
	}

	@Override
	public String getTargetVersion() {
		return "20171228";
	}

	@Override
	public void migrate(Connection connection) throws SpecmateException {
		// nothing to do
	}

}
