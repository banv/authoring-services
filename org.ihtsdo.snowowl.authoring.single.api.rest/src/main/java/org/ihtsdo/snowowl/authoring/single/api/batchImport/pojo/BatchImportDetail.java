package org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo;

public class BatchImportDetail {
	
	boolean loaded;
	String failureReason;
	String sctidCreated;
	
	public BatchImportDetail (boolean loaded, String failureReason, String sctidCreated) {
		this.loaded = loaded;
		this.failureReason = failureReason;
		this.sctidCreated = sctidCreated;
	}
	
	public boolean isLoaded() {
		return loaded;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Object getSctidCreated() {
		return this.sctidCreated;
	}

}