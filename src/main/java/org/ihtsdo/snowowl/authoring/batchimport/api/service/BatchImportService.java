package org.ihtsdo.snowowl.authoring.batchimport.api.service;

import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClient;
import org.ihtsdo.otf.rest.client.snowowl.pojo.*;
import org.ihtsdo.otf.rest.exception.BadRequestException;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.otf.rest.exception.ProcessingException;
import org.ihtsdo.snowowl.authoring.batchimport.api.client.AuthoringServicesClient;
import org.ihtsdo.snowowl.authoring.batchimport.api.client.AuthoringServicesClientException;
import org.ihtsdo.snowowl.authoring.batchimport.api.pojo.batch.*;
import org.ihtsdo.snowowl.authoring.batchimport.api.pojo.task.AuthoringTask;
import org.ihtsdo.snowowl.authoring.batchimport.api.pojo.task.AuthoringTaskCreateRequest;
import org.ihtsdo.snowowl.authoring.batchimport.api.service.file.dao.ArbitraryTempFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.b2international.commons.AcceptHeader;
import com.b2international.commons.ExtendedLocale;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class BatchImportService implements SnomedBrowserConstants{
	
	//We need this created for each request, since it will use the authentication
	//cookie of the requesting user
	//private AuthoringServicesClient authoringServicesClient;
	
	private ArbitraryTempFileService fileService = new ArbitraryTempFileService("batch_import");
	
	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	private ExecutorService executor = null;
	
	private static final String[] LATERALITY = new String[] { "left", "right"};
	
	private static final int ROW_UNKNOWN = -1;
	private static final String DRY_RUN = "DRY_RUN";
	private static final int DEFAULT_GROUP = 0;
	
	private static final String EDIT_PANEL = "edit-panel";
	private static final String SAVE_LIST = "saved-list";	
	private static final String NO_NOTES = "Concept import pending...";
	
	private List<ExtendedLocale> defaultLocales;
	private static final String defaultLocaleStr = "en-US;q=0.8,en-GB;q=0.6";
	private static final Map<String, String> ACCEPTABLE_ACCEPTABILIY = new HashMap<>();
	static {
		ACCEPTABLE_ACCEPTABILIY.put(SCTID_EN_GB, Acceptability.ACCEPTABLE.toString());
		ACCEPTABLE_ACCEPTABILIY.put(SCTID_EN_US, Acceptability.ACCEPTABLE.toString());
	}
	private static final Map<String, String> PREFERRED_ACCEPTABILIY = new HashMap<>();
	static {
		PREFERRED_ACCEPTABILIY.put(SCTID_EN_GB, Acceptability.PREFERRED.toString());
		PREFERRED_ACCEPTABILIY.put(SCTID_EN_US, Acceptability.PREFERRED.toString());
	}	
	
	private Map<UUID, BatchImportStatus> currentImports = new HashMap<>();
	
	public BatchImportService() throws BadRequestException {
		try {
			defaultLocales = AcceptHeader.parseExtendedLocales(new StringReader(defaultLocaleStr));
			executor = Executors.newFixedThreadPool(1); //Want this to be Async, but not expecting more than 1 to run at a time.
		} catch (IOException | IllegalArgumentException e) {
			throw new BadRequestException(e.getMessage());
		}
	}
	
	public void startImport(UUID batchImportId, BatchImportRequest importRequest, List<CSVRecord> rows, String currentUser, AuthoringServicesClient asClient, SnowOwlRestClient soClient) throws BusinessServiceException {
		BatchImportRun run = BatchImportRun.createRun(batchImportId, importRequest);
		currentImports.put(batchImportId, new BatchImportStatus(BatchImportState.RUNNING));
		prepareConcepts(run, rows);
		int rowsToProcess = run.getRootConcept().childrenCount();
		setTarget(run.getId(), rowsToProcess);
		logger.info("Batch Importing {} concepts onto new tasks in project {} - batch import id {} ",rowsToProcess, run.getImportRequest().getProjectKey(), run.getId().toString());
		
		if (validateLoadHierarchy(run)) {
			BatchImportRunner runner = new BatchImportRunner(run, this, asClient, soClient);
			executor.execute(runner);
		} else {
			run.abortLoad(rows);
			getBatchImportStatus(run.getId()).setState(BatchImportState.FAILED);
			logger.info("Batch Importing failed in project {} - batch import id {} ",run.getImportRequest().getProjectKey(), run.getId().toString());
			outputCSV(run);
		}
	}

	private void prepareConcepts(BatchImportRun run, List<CSVRecord> rows) throws BusinessServiceException {
		// Loop through concepts and form them into a hierarchy to be loaded, if valid
		int minViableColumns = run.getImportRequest().getFormat().getHeaders().length;
		for (CSVRecord thisRow : rows) {
			if (thisRow.size() >= minViableColumns) {
				try {
					BatchImportConcept thisConcept = run.getFormatter().createConcept(thisRow);
					if (validate(run, thisConcept)) {
						run.insertIntoLoadHierarchy(thisConcept);
					}
				} catch (Exception e) {
					run.fail(thisRow, e.getMessage());
				}
			} else {
				run.fail(thisRow, "Blank row detected");
			}
		}
	}

	private boolean validateLoadHierarchy(BatchImportRun run) {
		//Parents and children have to exist in the same task, so 
		//check that we're not going to exceed "concepts per task"
		//in doing so.
		boolean valid = true;
		for (BatchImportConcept thisConcept : run.getRootConcept().getChildren()) {
			if (thisConcept.childrenCount() >= run.getImportRequest().getConceptsPerTask()) {
				String failureMessage = "Concept " + thisConcept.getSctid() + " at row " + thisConcept.getRow().getRecordNumber() + " has more children than allowed for a single task";
				run.fail(thisConcept.getRow(), failureMessage);
				logger.error(failureMessage + " Aborting batch import.");
				valid = false;
			}
		}
		return valid;
	}

	
	private boolean validate(BatchImportRun run, BatchImportConcept concept) {
		if (!concept.requiresNewSCTID() && !validateSCTID(concept.getSctid())) {
			run.fail(concept.getRow(), concept.getSctid() + " is not a valid sctid.");
			return false;
		}
		
		if (!run.getFormatter().definesByExpression() && !validateSCTID(concept.getParent(0))) {
			run.fail(concept.getRow(), concept.getParent(0) + " is not a valid parent identifier.");
			return false;
		}
		
		if (run.getFormatter().definesByExpression()) {
			try{
				//Expression expression = (Expression) SCGStandaloneSetup.parse(concept.getExpressionStr());
				BatchImportExpression exp = BatchImportExpression.parse(concept.getExpressionStr());
				if (exp.getFocusConcepts() == null || exp.getFocusConcepts().size() < 1) {
					throw new ProcessingException("Unable to determine a parent for concept from expression");
				} 
				String parentStr = exp.getFocusConcepts().get(0);
				//Check we've got an integer (ok a long) for a parent
				try {
					Long.parseLong(parentStr);
				} catch (NumberFormatException ne) {
					throw new ProcessingException("Failed to correctly determine parent in expression: " + concept.getExpressionStr(),ne);
				}
				concept.addParent(parentStr);
				concept.setExpression(exp);
			} catch (NullPointerException np) {
				run.fail(concept.getRow(), "API coding exception: NullPointerException.  See logs for details");
				logger.error(ExceptionUtils.getStackTrace(np));
				return false;
			} catch (Exception e) {
				run.fail(concept.getRow(), "Invalid expression: " + e.getMessage());
				return false;
			}
		}
		return true;
	}
	
	private boolean validateSCTID(String sctid) {
		try{
			return VerhoeffCheck.validateLastChecksumDigit(sctid);
		} catch (Exception e) {
			//It's wrong, that's all we need to know.
		}
		return false;
	}

	void loadConceptsOntoTasks(BatchImportRun run, 
			AuthoringServicesClient asClient, 
			SnowOwlRestClient soClient) throws AuthoringServicesClientException, BusinessServiceException {
		List<List<BatchImportConcept>> batches = collectIntoBatches(run);
		for (List<BatchImportConcept> thisBatch : batches) {
			AuthoringTask task = createTask(run, thisBatch, asClient, soClient);
			Map<String, ConceptPojo> conceptsLoaded = loadConcepts(run, task, thisBatch, soClient);
			String conceptsLoadedJson = conceptList(conceptsLoaded.values());
			boolean dryRun = run.getImportRequest().isDryRun();
			logger.info((dryRun?"Dry ":"") + "Loaded concepts onto task {}: {}",task.getKey(),conceptsLoadedJson);
			if (!dryRun) {
				// Update the edit panel so that the UI state exists before the task is transferred
				if (conceptsLoadedJson.length() > 2) {
					primeEditPanel(task, run, conceptsLoadedJson, asClient);
					primeSavedList(task, run, conceptsLoaded.values(), asClient);
				} else {
					logger.info("Skipped update of UI-Panel for {}: {}",task.getKey(),conceptsLoadedJson);
				}
				
				//If we are loading 1 concept per task, then set the summary to be the FSN
				String newSummary = null;
				if (run.getImportRequest().getConceptsPerTask() == 1) {
					newSummary = "New concept: " + thisBatch.get(0).getFsn();
				}
				updateTaskDetails(task, run, conceptsLoaded, newSummary, asClient);
			}
		}
	}

	private void updateTaskDetails(AuthoringTask task, BatchImportRun run,
			Map<String, ConceptPojo> conceptsLoaded, String newSummary, 
			AuthoringServicesClient authoringServicesClient) {
		try {
			String allNotes = getAllNotes(task, run, conceptsLoaded);
			if (newSummary != null) {
				task.setSummary(newSummary);
			}
			task.setDescription(allNotes);
			authoringServicesClient.updateTask( task.getProjectKey(), 
												task.getKey(),
												task.getSummary(),
												task.getDescription(),
												run.getImportRequest().getCreateForAuthor());
			logger.info ("Task {} assigned to {}", task.getKey(), run.getImportRequest().getCreateForAuthor());
		} catch (Exception e) {
			logger.error("Failed to update description on task {}",task.getKey(),e);
		}
	}

	private void primeEditPanel(AuthoringTask task, BatchImportRun run, String conceptsJson, AuthoringServicesClient authoringServicesClient) {
		try {
			String user = run.getImportRequest().getCreateForAuthor();
			authoringServicesClient.persistTaskPanelState(task.getProjectKey(), task.getKey(), user, EDIT_PANEL, conceptsJson);
		} catch (AuthoringServicesClientException e) {
			logger.warn("Failed to prime edit panel for task " + task.getKey(), e );
		}
	}
	
	private String conceptList(Collection<ConceptPojo> concepts) {
		StringBuilder json = new StringBuilder("[");
		boolean isFirst = true;
		for (ConceptPojo thisConcept : concepts) {
			json.append( isFirst? "" : ",");
			json.append("\"").append(thisConcept.getConceptId()).append("\"");
			isFirst = false;
		}
		json.append("]");
		return json.toString();
	}
	
	private void primeSavedList(AuthoringTask task, BatchImportRun run, Collection<ConceptPojo> conceptsLoaded, AuthoringServicesClient authoringServicesClient) {
		try {
			String user = run.getImportRequest().getCreateForAuthor();
			StringBuilder json = new StringBuilder("{\"items\":[");
			boolean isFirst = true;
			for (ConceptPojo thisConcept : conceptsLoaded) {
				json.append( isFirst? "" : ",");
				json.append(toSavedListJson(thisConcept));
				isFirst = false;
			}
			json.append("]}");
			authoringServicesClient.persistTaskPanelState(task.getProjectKey(), task.getKey(), user, SAVE_LIST, json.toString());
		} catch (AuthoringServicesClientException e) {
			logger.warn("Failed to prime saved list for task " + task.getKey(), e );
		}
	}

	private StringBuilder toSavedListJson(ConceptPojo thisConcept) {
		StringBuilder buff = new StringBuilder("{\"concept\":");
		buff.append("{\"conceptId\":\"")
			.append(thisConcept.getConceptId())
			.append("\",\"fsn\":\"")
			.append(thisConcept.getFsn())
			.append("\"}}");
		return buff;
	}

	private List<List<BatchImportConcept>> collectIntoBatches(BatchImportRun run) {
		List<List<BatchImportConcept>> batches = new ArrayList<>();
	
		//Loop through all the children of root, starting a new batch every "concepts per task"
		List<BatchImportConcept> thisBatch = null;
		for (BatchImportConcept thisChild : run.getRootConcept().getChildren()) {
			if (thisBatch == null || thisBatch.size() >= run.getImportRequest().getConceptsPerTask()) {
				thisBatch = new ArrayList<>();
				batches.add(thisBatch);
			}
			//We can be sure that all descendants will not exceed our batch limit, having already validated
			thisBatch.add(thisChild);
			thisChild.addDescendants(thisBatch);
		}
		return batches;
	}

	private AuthoringTask createTask(BatchImportRun run,
			List<BatchImportConcept> thisBatch, 
			AuthoringServicesClient asClient,
			SnowOwlRestClient soClient) throws AuthoringServicesClientException {
		BatchImportRequest request = run.getImportRequest();
		AuthoringTaskCreateRequest taskCreateRequest = new AuthoringTask();
		
		//We'll re-do the description once we know which concepts actually loaded
		taskCreateRequest.setDescription(NO_NOTES);
		String taskSummary = request.getOriginalFilename() + ": " + getRowRange(thisBatch);
		taskCreateRequest.setSummary(taskSummary);

		AuthoringTask task = null;
		if (!request.isDryRun()) {
			task = asClient.createTask(request.getProjectKey(), taskCreateRequest);
			
			//That creates a task in Jira, but we also have to ask the TS to create one so as to actually obtain a branch
			try {
				soClient.createProjectTaskIfNeeded(request.getProjectKey(), task.getKey());
			} catch (RestClientException e) {
				throw new AuthoringServicesClientException("Failed to create task in TS", e);
			}
		} else {
			task = new AuthoringTask();
			task.setProjectKey(request.getProjectKey());
			task.setKey(DRY_RUN);
		}
		return task;
	}

	private String getRowRange(List<BatchImportConcept> thisBatch) {
		StringBuilder str = new StringBuilder ("Rows ");
		long minRow = ROW_UNKNOWN;
		long maxRow = ROW_UNKNOWN;
		
		for (BatchImportConcept thisConcept : thisBatch) {
			long thisRowNum = thisConcept.getRow().getRecordNumber();
			if (minRow == ROW_UNKNOWN || thisRowNum < minRow) {
				minRow = thisRowNum;
			}
			
			if (maxRow == ROW_UNKNOWN || thisRowNum > maxRow) {
				maxRow = thisRowNum;
			}
		}
		str.append(minRow).append(" - ").append(maxRow);
		return str.toString();
	}

	/*private String getAllNotes(BatchImportRun run,
			List<BatchImportConcept> thisBatch) throws BusinessServiceException {
		StringBuilder str = new StringBuilder();
		for (BatchImportConcept thisConcept : thisBatch) {
			str.append(JIRA_HEADING5)
			.append(thisConcept.getSctid())
			.append(":")
			.append(NEW_LINE);
			List<String> notes = run.getFormatter().getAllNotes(thisConcept);
			for (String thisNote: notes) {
				str.append(BULLET)
					.append(thisNote)
					.append(NEW_LINE);
			}
			str.append(NEW_LINE);
		}
		return str.toString();
	}*/
	// Temporary version using html formatting until WRP-2372 gets done
	private String getAllNotes(AuthoringTask task, BatchImportRun run,
							   Map<String, ConceptPojo> conceptsLoaded) throws BusinessServiceException {
		StringBuilder str = new StringBuilder();
		BatchImportFormat format = run.getFormatter();
		for (Map.Entry<String, ConceptPojo> thisEntry: conceptsLoaded.entrySet()) {
			String thisOriginalSCTID = thisEntry.getKey();
			BatchImportConcept biConcept = run.getConcept(thisOriginalSCTID);
			ConceptPojo thisConcept = thisEntry.getValue();
			str.append("<h5>")
			.append(thisConcept.getConceptId())
			.append(" - ")
			.append(thisConcept.getFsn())
			.append(":</h5>")
			.append("<ul>");
			
			if (format.getIndex(BatchImportFormat.FIELD.ORIG_REF) != BatchImportFormat.FIELD_NOT_FOUND) {
				String origRef = biConcept.get(format.getIndex(BatchImportFormat.FIELD.ORIG_REF));
				if (origRef != null && !origRef.isEmpty()) {
					str.append("<li>Originating Reference: ")
					.append(origRef)
					.append("</li>");
				}
			}
			
			for (int docIdx : format.getDocumentationFields()) {
				String docStr = biConcept.get(docIdx);
				if (docStr != null && !docStr.isEmpty()) {
					str.append("<li>")
					.append(format.getHeaders()[docIdx])
					.append(": ")
					.append(docStr)
					.append("</li>");
				}
			}
			
			List<String> notes = run.getFormatter().getAllNotes(biConcept);
			for (String thisNote: notes) {
				str.append("<li>")
					.append(thisNote)
					.append("</li>");
			}
			str.append("</ul>");
		}
		return str.toString();
	}

	private Map<String, ConceptPojo> loadConcepts(BatchImportRun run, AuthoringTask task,
			List<BatchImportConcept> thisBatch, SnowOwlRestClient soClient) throws BusinessServiceException {
		BatchImportRequest request = run.getImportRequest();
		String branchPath = "MAIN/" + request.getProjectKey() + "/" + task.getKey();
		
		Map<String, ConceptPojo> conceptsLoaded = new HashMap<>();
		for (BatchImportConcept thisConcept : thisBatch) {
			boolean loadedOK = false;
			try{
				ConceptPojo newConcept = createBrowserConcept(thisConcept, run.getFormatter());
				String warnings = "";
				validateConcept(run, task, newConcept);
				removeTemporaryIds(newConcept);
				ConceptPojo createdConcept;
				if (!request.isDryRun()) {
					//createdConcept = browserService.create(branchPath, newConcept, run.getImportRequest().getCreateForAuthor(), defaultLocales);
					createdConcept = soClient.createConcept(branchPath, newConcept);
				} else {
					ConceptPojo dryRunConcept = new ConceptPojo();
					dryRunConcept.setConceptId(DRY_RUN);
					createdConcept = dryRunConcept;
				}
				String msg = "Loaded onto " + task.getKey() + " " + warnings;
				run.succeed(thisConcept.getRow(), msg, createdConcept.getConceptId());
				loadedOK = true;
				conceptsLoaded.put(thisConcept.getSctid(),createdConcept);
			} catch (BusinessServiceException b) {
				//Somewhat expected error, no need for full stack trace
				run.fail(thisConcept.getRow(), b.getMessage());
			} catch (Exception e) {
				run.fail(thisConcept.getRow(), e.getMessage());
				logger.error("Exception during Batch Import at line {}", thisConcept.getRow().getRecordNumber(), e);
			}
			incrementProgress(run.getId(), loadedOK);
		}
		return conceptsLoaded;
	}
	
	/**
	 * We assigned temporary text ids so that we could tell the user which components failed validation
	 * but we don't want to save those, so remove.
	 * @param newConcept
	 */
	private void removeTemporaryIds(ConceptPojo newConcept) {
		//Casting is quicker than recreating the lists and replacing
		for (DescriptionPojo thisDesc : newConcept.getDescriptions()) {
			((DescriptionPojo)thisDesc).setDescriptionId(null);
		}
		
		for (RelationshipPojo thisRel : newConcept.getRelationships()) {
			((RelationshipPojo)thisRel).setRelationshipId(null);
		}
	}
	
	private String validateConcept(BatchImportRun run, AuthoringTask task,
								   ConceptPojo newConcept) throws BusinessServiceException {
		
		if (!run.getImportRequest().isLateralizedContentAllowed()) {
			//Check for lateralized content
			for (String thisBadWord : LATERALITY) {
				if (newConcept.getFsn().toLowerCase().contains(thisBadWord)) {
					throw new BusinessServiceException ("Lateralized content detected");
				}
			}
		}
		return null;
	}

	private ConceptPojo createBrowserConcept(
			BatchImportConcept thisConcept, BatchImportFormat formatter) throws BusinessServiceException {
		ConceptPojo newConcept = new ConceptPojo();
		if (!thisConcept.requiresNewSCTID()) {
			newConcept.setConceptId(thisConcept.getSctid());
		}
		newConcept.setActive(true);


		Set<RelationshipPojo> relationships;
		if (formatter.definesByExpression()) {
			relationships = convertExpressionToRelationships(thisConcept.getSctid(), thisConcept.getExpression());
			newConcept.setDefinitionStatus(thisConcept.getExpression().getDefinitionStatus());
		} else {
			newConcept.setDefinitionStatus(DefinitionStatus.PRIMITIVE);
			//Set the Parent
			relationships = new HashSet<RelationshipPojo>();
			for (String thisParent : thisConcept.getParents()) {
				RelationshipPojo isA = createRelationship(DEFAULT_GROUP, "rel_isa", thisConcept.getSctid(), SCTID_ISA, thisParent);
				relationships.add(isA);
			}
		}
		newConcept.setRelationships(relationships);
		
		//Add Descriptions FSN, then Preferred Term
		Set<DescriptionPojo> descriptions = new HashSet<>();
		String prefTerm = null, fsnTerm;
		
		if (formatter.constructsFSN()) {
			prefTerm = thisConcept.get(formatter.getIndex(BatchImportFormat.FIELD.FSN_ROOT));
			fsnTerm = prefTerm + " (" + thisConcept.get(formatter.getIndex(BatchImportFormat.FIELD.SEMANTIC_TAG)) +")";
		} else {
			fsnTerm = thisConcept.get(formatter.getIndex(BatchImportFormat.FIELD.FSN));
			if (!formatter.hasMultipleTerms()) {
				prefTerm = thisConcept.get(formatter.getIndex(BatchImportFormat.FIELD.PREF_TERM));
			}
		}
		
		String languageCode = "en";
		
		//Set the FSN
		CaseSignificance fsnCase = CaseSignificance.CASE_INSENSITIVE;  //default
		if (formatter.getIndex(BatchImportFormat.FIELD.CAPSFSN) != BatchImportFormat.FIELD_NOT_FOUND) {
			fsnCase = translateCaseSensitivity(thisConcept.get(formatter.getIndex(BatchImportFormat.FIELD.CAPSFSN)));
		}
		
		thisConcept.setFsn(fsnTerm);
		DescriptionPojo fsn = createDescription(fsnTerm, DescriptionType.FSN, PREFERRED_ACCEPTABILIY, languageCode, fsnCase);
		descriptions.add(fsn);
		
		if (formatter.hasMultipleTerms()) {
			int termIdx = 0;
			for (BatchImportTerm biTerm : thisConcept.getTerms()) {
				DescriptionPojo term = createDescription(biTerm, termIdx);
				descriptions.add(term);
				termIdx++;
			}
		} else {
			DescriptionPojo pref = createDescription(prefTerm, DescriptionType.SYNONYM, PREFERRED_ACCEPTABILIY, languageCode, CaseSignificance.CASE_INSENSITIVE);
			descriptions.add(pref);
			addSynonyms(descriptions, formatter, languageCode, thisConcept);
		}
		newConcept.setDescriptions(descriptions);
		
		return newConcept;
	}

	Set<RelationshipPojo> convertExpressionToRelationships(String sourceSCTID,
			BatchImportExpression expression) {
		Set<RelationshipPojo> relationships = new HashSet<>();
		
		int parentNum = 0;
		for (String thisParent : expression.getFocusConcepts()) {
			RelationshipPojo rel = createRelationship(DEFAULT_GROUP, "rel_" + (parentNum++), sourceSCTID, SCTID_ISA, thisParent);
			relationships.add(rel);
		}
		
		for (BatchImportGroup group : expression.getAttributeGroups()) {
			relationships.addAll(group.getRelationships());
		}
		
		return relationships;
	}

	private void addSynonyms(Set<DescriptionPojo> descriptions,
			BatchImportFormat formatter, String languageCode, BatchImportConcept thisConcept) throws BusinessServiceException {
		List<String> allSynonyms = formatter.getAllSynonyms(thisConcept);
		for (String thisSyn : allSynonyms) {
			if (!containsDescription (descriptions, thisSyn)){
				DescriptionPojo syn =  createDescription(thisSyn, DescriptionType.SYNONYM, ACCEPTABLE_ACCEPTABILIY, languageCode, CaseSignificance.CASE_INSENSITIVE);
				descriptions.add(syn);
			}
		}
	}

	/**
	 * @param descriptions
	 * @param term
	 * @return true if the list of descriptions already contains this term
	 */
	private boolean containsDescription(Set<DescriptionPojo> descriptions, String term) {
		for (DescriptionPojo thisDesc : descriptions) {
			if (thisDesc.getTerm().equals(term)) {
				return true;
			}
		}
		return false;
	}

	public static RelationshipPojo createRelationship(int groupNum, String tmpId, String sourceSCTID, String typeSCTID, String destinationSCTID) {
		RelationshipPojo rel = new RelationshipPojo();
		rel.setGroupId(groupNum);
		rel.setCharacteristicType(CharacteristicType.STATED.toString());
		rel.setSourceId(sourceSCTID);
		rel.setType(new ConceptMiniPojo(typeSCTID));
		//Set a temporary id so the user can tell which item failed validation
		rel.setRelationshipId(tmpId);
		ConceptMiniPojo destination = new ConceptMiniPojo(destinationSCTID);
		rel.setTarget(destination);
		rel.setActive(true);
		rel.setModifier(RelationshipModifier.EXISTENTIAL.toString());
		return rel;
	}
	
	private DescriptionPojo createDescription(String term, DescriptionType type, Map<String, String> acceptabilityMap, String lang, CaseSignificance caseSig) {
		DescriptionPojo desc = new DescriptionPojo();
		//Set a temporary id so the user can tell which item failed validation
		desc.setDescriptionId("desc_" + type.toString());
		desc.setTerm(term);
		desc.setActive(true);
		desc.setType(type.toString());
		//TODO The language will have to be passed in with the batch import parameters
		desc.setLang(lang);
		desc.setAcceptabilityMap(acceptabilityMap);
		desc.setCaseSignificance(caseSig.toString());
		return desc;
	}
	
	private DescriptionPojo createDescription(BatchImportTerm biTerm, int idx) throws BusinessServiceException {
		DescriptionPojo desc = new DescriptionPojo();
		//Set a temporary id so the user can tell which item failed validation
		desc.setDescriptionId("desc_SYN_" + idx);
		desc.setTerm(biTerm.getTerm());
		desc.setActive(true);
		desc.setType(DescriptionType.SYNONYM.toString());
		desc.setLang(EN_LANGUAGE_CODE);
		desc.setAcceptabilityMap(getAcceptablityAsMap(biTerm));
		if (biTerm.getCaseSensitivity() == null || biTerm.getCaseSensitivity().isEmpty()) {
			desc.setCaseSignificance(CaseSignificance.CASE_INSENSITIVE.toString());
		} else {
			desc.setCaseSignificance(translateCaseSensitivity(biTerm.getCaseSensitivity()).toString());
		}
		return desc;
	}

	private static CaseSignificance translateCaseSensitivity(String caseSensitivity) throws BusinessServiceException {
		switch (caseSensitivity) {
			case "CS" : return CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE;
			case "ci" : return CaseSignificance.CASE_INSENSITIVE;
			case "cI" : return CaseSignificance.INITIAL_CHARACTER_CASE_INSENSITIVE;
			default : throw new BusinessServiceException ("Could not determine case significance from " + caseSensitivity);
		}
	}
	
	private static Acceptability translateAcceptability(char acceptability) throws BusinessServiceException {
		switch (Character.toUpperCase(acceptability)) {
			case 'N' : return null;
			case 'P' : return Acceptability.PREFERRED;
			case 'A' : return Acceptability.ACCEPTABLE;
			default : throw new BusinessServiceException ("Could not determine acceptability from '" + acceptability + "'");
		}
	}

	private static Map<String, String> getAcceptablityAsMap(BatchImportTerm term) throws BusinessServiceException {
		Map <String, String> acceptabilityMap = new HashMap<>();
		Acceptability gbAcceptability = translateAcceptability(term.getAcceptabilityGB());
		if (gbAcceptability != null) {
			acceptabilityMap.put(SCTID_EN_GB, gbAcceptability.toString());
		}
		
		Acceptability usAcceptability = translateAcceptability(term.getAcceptabilityUS());
		if (usAcceptability != null) {
			acceptabilityMap.put(SCTID_EN_US, usAcceptability.toString());
		}
		return acceptabilityMap;
	}

	private String getFilePath(BatchImportRun run) {
		String fileLocation = getFileLocation(run.getImportRequest().getProjectKey(), run.getId().toString());
		return fileLocation + File.separator + run.getImportRequest().getOriginalFilename();
	}
	
	private String getFileLocation(String projectKey, String uuid) {
		return projectKey + File.separator + uuid ;
	}

	public BatchImportStatus getImportStatus(UUID batchImportId) {
		return currentImports.get(batchImportId);
	}
	
	public File getImportResultsFile(String projectKey, UUID batchImportId) {
		File resultDir = new File (getFileLocation(projectKey, batchImportId.toString()));
		return fileService.listFiles(resultDir.getPath())[0];
	}

	public String getImportResults(String projectKey, UUID batchImportId) throws IOException {
		File resultFile = getImportResultsFile(projectKey, batchImportId);
		return fileService.read(resultFile);
	}
	
	synchronized private void setTarget(UUID batchImportId, Integer rowsToProcess) {
		BatchImportStatus status = getBatchImportStatus(batchImportId);
		status.setTarget(rowsToProcess);
	}
	
	synchronized private void incrementProgress(UUID batchImportId, boolean loaded) {
		BatchImportStatus status = getBatchImportStatus(batchImportId);
		status.setProcessed(status.getProcessed() == null ? 1 : status.getProcessed() + 1);
		if (loaded) {
			status.setLoaded(status.getLoaded() == null ? 1 : status.getLoaded() + 1);
		}
	}
	
	synchronized BatchImportStatus getBatchImportStatus(UUID batchImportId) {
		BatchImportStatus status = currentImports.get(batchImportId);
		if (status == null) {
			status = new BatchImportStatus (BatchImportState.RUNNING);
			currentImports.put(batchImportId, status);
		}
		return status;
	}

	public void outputCSV(BatchImportRun batchImportRun) {
		try {
			fileService.write(getFilePath(batchImportRun), batchImportRun.resultsAsCSV());
		} catch (Exception e) {
			logger.error("Failed to save results of batch import",e);
		}
	}

}