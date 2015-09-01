package org.ihtsdo.snowowl.authoring.single.api.service.dao;

import com.b2international.snowowl.core.ApplicationContext;
import com.b2international.snowowl.core.config.SnowOwlConfiguration;
import com.b2international.snowowl.datastore.config.RepositoryConfiguration;

import java.sql.*;
import java.util.Date;
import java.util.*;

public class CdoStore {
	
	private Map <CDOStatement, PreparedStatement> preparedStatements;
	
	private static final String DB_NAME = "snomedStore";
	private static final String DB_ADDITIONAL_PROPERTIES = "&autoReconnect=true";
	
	enum CDOStatement { GET_BRANCH_ID, GET_CONCEPTS_CHANGED_SINCE };
	
	Connection conn;
	
	private static final String getBranchId = "select childBranch.id "
		+ " from cdo_branches childBranch,  "
		+ " cdo_branches parentBranch "
		+ " where childBranch.base_id = parentBranch.id "
		+ " and childBranch.name = ? "
		+ " and parentBranch.name = ?";
	
	private static final String getConceptsChangedSince = " SELECT distinct id "
			+ "FROM snomed_concept where cdo_branch = ? "
			+" and cdo_created >= ? ";
	
	public void init() throws SQLException {
	
		getDBConn();
		preparedStatements = new HashMap<>();
		preparedStatements.put(CDOStatement.GET_BRANCH_ID, conn.prepareStatement(getBranchId));
		preparedStatements.put(CDOStatement.GET_CONCEPTS_CHANGED_SINCE, conn.prepareStatement(getConceptsChangedSince));
	}
	
	private void getDBConn() throws SQLException {
		//Create a new database configuration based on the current repository configuration
		RepositoryConfiguration config = ApplicationContext.getInstance().getServiceChecked(SnowOwlConfiguration.class).getModuleConfig(RepositoryConfiguration.class);
		Map<Object, Object> propertyMap = config.getDatasourceProperties(DB_NAME);
		Properties dbProperties = new Properties();
		dbProperties.putAll(propertyMap);
		String dbUrl = propertyMap.get("uRL").toString() + DB_ADDITIONAL_PROPERTIES;
		conn = DriverManager.getConnection(dbUrl, dbProperties);
	}
	
	public Integer getBranchId(String parentBranchName, String childBranchName) throws SQLException {
		
		PreparedStatement stmt = preparedStatements.get(CDOStatement.GET_BRANCH_ID);
		stmt.setString(1, childBranchName);
		stmt.setString(2, parentBranchName);
		ResultSet rs = stmt.executeQuery();
		return rs.next() ? new Integer (rs.getInt(1)) : null; 
	}
	
	/**
	 * Returns a list of all concepts modified (including descriptions and relationships)
	 * since a given date
	 * @param branchId
	 * @param fromDate
	 * @return
	 * @throws SQLException
	 */
	public Set<String> getConceptChanges(Integer branchId, Date fromDate) throws SQLException {
		
		PreparedStatement stmt = preparedStatements.get(CDOStatement.GET_CONCEPTS_CHANGED_SINCE);
		stmt.setInt(1, branchId);
		stmt.setLong(2, fromDate.getTime());
		ResultSet rs = stmt.executeQuery();
		Set<String> conceptsChanged = new HashSet<String>();
		while (rs.next()) {
			conceptsChanged.add(rs.getString(1));
		}
		return conceptsChanged; 
	}

}