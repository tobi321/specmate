package com.specmate.migration.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.specmate.common.SpecmateException;

public class SQLMigrationStep {
	private String query;
	private String failmsg;
	private PreparedStatement stmt;
	
	
	public SQLMigrationStep(String query, String failmsg) {
		this.query = query;
		this.failmsg = failmsg;
		this.stmt = null;
	}
	
	public SQLMigrationStep(String query) {
		this(query, null);
	}
	
	public void execute(Connection connection) throws SQLException {
		if (stmt == null) {
			stmt = connection.prepareStatement(query);
		}
		stmt.execute();
	}
	
	public void close() throws SQLException {
		if (stmt != null) {
			stmt.close();
		}
	}
	
	public String getFailMsg() {
		return failmsg == null ? "" : failmsg;
	}
	
	public void setString(int index, String str, Connection connection) throws SpecmateException {
		try {
			if (stmt == null) {
				stmt = connection.prepareStatement(query);
			}
			stmt.setString(index, str);
		} catch (SQLException e) {
			throw new SpecmateException("Could not set string parameter in prepared statement.", e);
		}
	}
	
	public void setBytes(int index, byte[] bs, Connection connection) throws SpecmateException {
		try {
			if (stmt == null) {
				stmt = connection.prepareStatement(query);
			}
			stmt.setBytes(index, bs);
		} catch (SQLException e) {
			throw new SpecmateException("Could not set byte array parameter in prepared statement.", e);
		}
	}
	
	public void setLong(int index, long lg, Connection connection) throws SpecmateException {
		try {
			if (stmt == null) {
				stmt = connection.prepareStatement(query);
			}
			stmt.setLong(index, lg);
		} catch (SQLException e) {
			throw new SpecmateException("Could not set ong parameter in prepared statement.", e);
		}
	}
}