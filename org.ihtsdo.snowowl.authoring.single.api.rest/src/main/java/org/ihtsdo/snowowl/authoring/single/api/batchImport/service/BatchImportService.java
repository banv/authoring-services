package org.ihtsdo.snowowl.authoring.single.api.batchImport.service;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.rcarz.jiraclient.JiraException;

import org.apache.commons.csv.CSVRecord;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo.BatchImportConcept;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo.BatchImportRequest;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo.BatchImportRun;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo.BatchImportState;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.pojo.BatchImportStatus;
import org.ihtsdo.snowowl.authoring.single.api.batchImport.service.BatchImportFormat.FIELD;
import org.ihtsdo.snowowl.authoring.single.api.pojo.AuthoringTask;
import org.ihtsdo.snowowl.authoring.single.api.pojo.AuthoringTaskCreateRequest;
import org.ihtsdo.snowowl.authoring.single.api.service.BranchService;
import org.ihtsdo.snowowl.authoring.single.api.service.ServiceException;
import org.ihtsdo.snowowl.authoring.single.api.service.TaskService;
import org.ihtsdo.snowowl.authoring.single.api.service.UiStateService;
import org.ihtsdo.snowowl.authoring.single.api.service.dao.ArbitraryFileService;
import org.ihtsdo.snowowl.authoring.single.api.service.dao.ArbitraryTempFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.b2international.commons.VerhoeffCheck;
import com.b2international.commons.http.AcceptHeader;
import com.b2international.commons.http.ExtendedLocale;
import com.b2international.snowowl.core.exceptions.ApiError;
import com.b2international.snowowl.core.exceptions.BadRequestException;
import com.b2international.snowowl.core.exceptions.ValidationException;
import com.b2international.snowowl.eventbus.IEventBus;
import com.b2international.snowowl.snomed.SnomedConstants;
import com.b2international.snowowl.snomed.SnomedConstants.Concepts;
import com.b2international.snowowl.snomed.api.domain.browser.ISnomedBrowserConcept;
import com.b2international.snowowl.snomed.api.domain.browser.ISnomedBrowserDescription;
import com.b2international.snowowl.snomed.api.domain.browser.ISnomedBrowserRelationship;
import com.b2international.snowowl.snomed.api.domain.browser.SnomedBrowserDescriptionType;
import com.b2international.snowowl.snomed.api.impl.SnomedBrowserService;
import com.b2international.snowowl.snomed.api.impl.domain.browser.SnomedBrowserConcept;
import com.b2international.snowowl.snomed.api.impl.domain.browser.SnomedBrowserDescription;
import com.b2international.snowowl.snomed.api.impl.domain.browser.SnomedBrowserRelationship;
import com.b2international.snowowl.snomed.api.impl.domain.browser.SnomedBrowserRelationshipTarget;
import com.b2international.snowowl.snomed.api.impl.domain.browser.SnomedBrowserRelationshipType;
import com.b2international.snowowl.snomed.api.validation.ISnomedBrowserValidationService;
import com.b2international.snowowl.snomed.core.domain.Acceptability;
import com.b2international.snowowl.snomed.core.domain.CaseSignificance;
import com.b2international.snowowl.snomed.core.domain.CharacteristicType;
import com.b2international.snowowl.snomed.core.domain.DefinitionStatus;

@Service
public class BatchImportService {
	
	@Autowired
	private TaskService taskService;
	
	@Autowired
	SnomedBrowserService browserService;
	
	@Autowired
	UiStateService uiStateService;
	
	@Autowired
	private IEventBus eventBus;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ISnomedBrowserValidationService validationService;
	
	ArbitraryTempFileService fileService = new ArbitraryTempFileService("batch_import");
	
	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	private ExecutorService executor = null;
	
	private static final String[] LATERALITY = new String[] { "left", "right"};
	
	private static final int ROW_UNKNOWN = -1;
	//private static final String BULLET = "* ";
	private static final String SCTID_ISA = Concepts.IS_A;
	private static final String SCTID_EN_GB = Concepts.REFSET_LANGUAGE_TYPE_UK;
	private static final String SCTID_EN_US = Concepts.REFSET_LANGUAGE_TYPE_US;
	//private static final String JIRA_HEADING5 = "h5. ";
	//private static final String VALIDATION_ERROR = "ERROR";
	private static final int MIN_VIABLE_COLUMNS = 9;
	
	private static final String EDIT_PANEL = "edit-panel";
	private static final String SAVE_LIST = "saved-list";	
	private static final String NO_NOTES = "All concepts failed to load via Batch Import";
	
	private List<ExtendedLocale> defaultLocales;
	private static final String defaultLocaleStr = "en-US;q=0.8,en-GB;q=0.6";
	public static final Map<String, Acceptability> ACCEPTABLE_ACCEPTABILIY = new HashMap<String, Acceptability>();
	static {
		ACCEPTABLE_ACCEPTABILIY.put(SCTID_EN_GB, Acceptability.ACCEPTABLE);
		ACCEPTABLE_ACCEPTABILIY.put(SCTID_EN_US, Acceptability.ACCEPTABLE);
	}
	public static final Map<String, Acceptability> PREFERRED_ACCEPTABILIY = new HashMap<String, Acceptability>();
	static {
		PREFERRED_ACCEPTABILIY.put(SCTID_EN_GB, Acceptability.PREFERRED);
		PREFERRED_ACCEPTABILIY.put(SCTID_EN_US, Acceptability.PREFERRED);
	}	
	
	Map<UUID, BatchImportStatus> currentImports = new HashMap<UUID, BatchImportStatus>();
	
	public BatchImportService () {
		try {
			defaultLocales = AcceptHeader.parseExtendedLocales(new StringReader(defaultLocaleStr));
			executor = Executors.newFixedThreadPool(1); //Want this to be Async, but not expecting more than 1 to run at a time.
		} catch (IOException e) {
			throw new BadRequestException(e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new BadRequestException(e.getMessage());
		}
	}
	
	public void startImport(UUID batchImportId, BatchImportRequest importRequest, List<CSVRecord> rows, String currentUser) throws BusinessServiceException, JiraException, ServiceException {
		BatchImportRun run = BatchImportRun.createRun(batchImportId, importRequest);
		currentImports.put(batchImportId, new BatchImportStatus(BatchImportState.RUNNING));
		prepareConcepts(run, rows);
		int rowsToProcess = run.getRootConcept().childrenCount();
		setTarget(run.getId(), rowsToProcess);
		logger.info("Batch Importing {} concepts onto new tasks in project {} - batch import id {} ",rowsToProcess, run.getImportRequest().getProjectKey(), run.getId().toString());
		
		if (validateLoadHierarchy(run)) {
			BatchImportRunner runner = new BatchImportRunner(run, this);
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
		for (CSVRecord thisRow : rows) {
			if (thisRow.size() > MIN_VIABLE_COLUMNS) {
				BatchImportConcept thisConcept = run.getFormatter().createConcept(thisRow);
				if (validate(run, thisConcept)) {
					run.insertIntoLoadHierarchy(thisConcept);
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

	
	private boolean validate (BatchImportRun run, BatchImportConcept concept) {
		if (!concept.requiresNewSCTID() && !validateSCTID(concept.getSctid())) {
			run.fail(concept.getRow(), concept.getSctid() + " is not a valid sctid.");
			return false;
		}
		
		if (!validateSCTID(concept.getParent())) {
			run.fail(concept.getRow(), concept.getParent() + " is not a valid parent identifier.");
			return false;
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

	void loadConceptsOntoTasks(BatchImportRun run) throws JiraException, ServiceException, BusinessServiceException {
		List<List<BatchImportConcept>> batches = collectIntoBatches(run);
		for (List<BatchImportConcept> thisBatch : batches) {
			AuthoringTask task = createTask(run, thisBatch);
			Map<String, ISnomedBrowserConcept> conceptsLoaded = loadConcepts(run, task, thisBatch);
			String conceptsLoadedJson = conceptList(conceptsLoaded.values());
			logger.info("Loaded concepts onto task {}: {}",task.getKey(),conceptsLoadedJson);
			updateTaskDescription(task, run, conceptsLoaded);
			primeEditPanel(task, run, conceptsLoadedJson);
			primeSavedList(task, run, conceptsLoaded.values());
		}
	}

	private void updateTaskDescription(AuthoringTask task, BatchImportRun run,
			Map<String, ISnomedBrowserConcept> conceptsLoaded) {
		try {
			String allNotes = getAllNotes(task, run, conceptsLoaded);
			task.setDescription(allNotes);
			taskService.updateTask(task.getProjectKey(), task.getKey(), task);
		} catch (Exception e) {
			logger.error("Failed to update description on task {}",task.getKey(),e);
		}
		
	}

	private void primeEditPanel(AuthoringTask task, BatchImportRun run, String conceptsJson) {
		try {
			String user = run.getImportRequest().getCreateForAuthor();
			uiStateService.persistTaskPanelState(task.getProjectKey(), task.getKey(), user, EDIT_PANEL, conceptsJson);
		} catch (IOException e) {
			logger.warn("Failed to prime edit panel for task " + task.getKey(), e );
		}
	}
	
	private String conceptList(Collection<ISnomedBrowserConcept> concepts) {
		StringBuilder json = new StringBuilder("[");
		boolean isFirst = true;
		for (ISnomedBrowserConcept thisConcept : concepts) {
			json.append( isFirst? "" : ",");
			json.append("\"").append(thisConcept.getConceptId()).append("\"");
			isFirst = false;
		}
		json.append("]");
		return json.toString();
	}
	
	private void primeSavedList(AuthoringTask task, BatchImportRun run, Collection<ISnomedBrowserConcept> conceptsLoaded) {
		try {
			String user = run.getImportRequest().getCreateForAuthor();
			StringBuilder json = new StringBuilder("{\"items\":[");
			boolean isFirst = true;
			for (ISnomedBrowserConcept thisConcept : conceptsLoaded) {
				json.append( isFirst? "" : ",");
				json.append(toSavedListJson(thisConcept));
				isFirst = false;
			}
			json.append("]}");
			uiStateService.persistTaskPanelState(task.getProjectKey(), task.getKey(), user, SAVE_LIST, json.toString());
		} catch (IOException e) {
			logger.warn("Failed to prime saved list for task " + task.getKey(), e );
		}
	}

	private StringBuilder toSavedListJson(ISnomedBrowserConcept thisConcept) {
		StringBuilder buff = new StringBuilder("{\"concept\":");
		buff.append("{\"conceptId\":\"")
			.append(thisConcept.getConceptId())
			.append("\",\"fsn\":\"")
			.append(thisConcept.getFsn())
			.append("\"}}");
		return buff;
	}

	private List<List<BatchImportConcept>> collectIntoBatches(BatchImportRun run) {
		List<List<BatchImportConcept>> batches = new ArrayList<List<BatchImportConcept>>();
	
		//Loop through all the children of root, starting a new batch every "concepts per task"
		List<BatchImportConcept> thisBatch = null;
		for (BatchImportConcept thisChild : run.getRootConcept().getChildren()) {
			if (thisBatch == null || thisBatch.size() >= run.getImportRequest().getConceptsPerTask()) {
				thisBatch = new ArrayList<BatchImportConcept>();
				batches.add(thisBatch);
			}
			//We can be sure that all descendants will not exceed our batch limit, having already validated
			thisBatch.add(thisChild);
			thisChild.addDescendants(thisBatch);
		}
		return batches;
	}

	private AuthoringTask createTask(BatchImportRun run,
			List<BatchImportConcept> thisBatch) throws JiraException, ServiceException, BusinessServiceException {
		AuthoringTaskCreateRequest taskCreateRequest = new AuthoringTask();
		//We'll re-do the description once we know which concepts actually loaded
		taskCreateRequest.setDescription(NO_NOTES);
		String taskSummary = run.getImportRequest().getOriginalFilename() + ": " + getRowRange(thisBatch);
		taskCreateRequest.setSummary(taskSummary);
		AuthoringTask task = taskService.createTask(run.getImportRequest().getProjectKey(), 
				run.getImportRequest().getCreateForAuthor(),
				taskCreateRequest);
		//Task service now delays creation of actual task branch, so separate call to do that.
		branchService.createTaskBranchAndProjectBranchIfNeeded(task.getProjectKey(), task.getKey());
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
			Map<String, ISnomedBrowserConcept> conceptsLoaded) throws BusinessServiceException {
		StringBuilder str = new StringBuilder();
		for (Map.Entry<String, ISnomedBrowserConcept> thisEntry: conceptsLoaded.entrySet()) {
			String thisOriginalSCTID = thisEntry.getKey();
			ISnomedBrowserConcept thisConcept = thisEntry.getValue();
			str.append("<h5>")
			.append(thisConcept.getId())
			.append(" - ")
			.append(thisConcept.getFsn())
			.append(":</h5>")
			.append("<ul>");
			BatchImportConcept biConcept = run.getConcept(thisOriginalSCTID);
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

	private Map<String, ISnomedBrowserConcept> loadConcepts(BatchImportRun run, AuthoringTask task,
			List<BatchImportConcept> thisBatch) throws BusinessServiceException {
		String branchPath = "MAIN/" + run.getImportRequest().getProjectKey() + "/" + task.getKey();
		Map<String, ISnomedBrowserConcept> conceptsLoaded = new HashMap<String, ISnomedBrowserConcept>();
		for (BatchImportConcept thisConcept : thisBatch) {
			boolean loadedOK = false;
			try{
				ISnomedBrowserConcept newConcept = createBrowserConcept(thisConcept, run.getFormatter());
				String warnings = "";
				validateConcept(task, newConcept);
				removeTemporaryIds(newConcept);
				ISnomedBrowserConcept createdConcept = browserService.create(branchPath, newConcept, run.getImportRequest().getCreateForAuthor(), defaultLocales);
				String msg = "Loaded onto " + task.getKey() + " " + warnings;
				run.succeed(thisConcept.getRow(), msg, createdConcept.getId());
				loadedOK = true;
				conceptsLoaded.put(thisConcept.getSctid(),createdConcept);
			} catch (ValidationException v) {
				run.fail(thisConcept.getRow(), prettyPrint(v.toApiError()));
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
	private void removeTemporaryIds(ISnomedBrowserConcept newConcept) {
		//Casting is quicker than recreating the lists and replacing
		for (ISnomedBrowserDescription thisDesc : newConcept.getDescriptions()) {
			((SnomedBrowserDescription)thisDesc).setDescriptionId(null);
		}
		
		for (ISnomedBrowserRelationship thisRel : newConcept.getRelationships()) {
			((SnomedBrowserRelationship)thisRel).setRelationshipId(null);
		}
	}
	
	private String validateConcept(AuthoringTask task,
			ISnomedBrowserConcept newConcept) throws BusinessServiceException {
		
		//Check for lateralized content
		for (String thisBadWord : LATERALITY) {
			if (newConcept.getFsn().toLowerCase().contains(thisBadWord)) {
				throw new BusinessServiceException ("Lateralized content detected");
			}
		}
		return null;
	}
	

	/*private String validateConcept(AuthoringTask task,
			ISnomedBrowserConcept newConcept) throws BusinessServiceException {
		StringBuilder warnings = new StringBuilder();
		StringBuilder errors = new StringBuilder();
		List<ISnomedInvalidContent> validationIssues = validationService.validateConcept(getBranchPath(task), newConcept, defaultLocales);
		for (ISnomedInvalidContent thisIssue : validationIssues) {
			if (thisIssue.getSeverity().equals(VALIDATION_ERROR)) {
				errors.append(" ").append(thisIssue.getMessage());
			} else {
				warnings.append(" ").append(thisIssue.getMessage());
			}
		}
		if (errors.length() > 0) {
			throw new BusinessServiceException("Error for concept " + newConcept.getConceptId() + ": " + errors.toString());
		}
		return warnings.toString();
	}*/


	private String prettyPrint(ApiError v) {
		StringBuilder buff = new StringBuilder (v.getMessage());
		buff.append(" - ")
			.append(v.getDeveloperMessage())
			.append(": [ ");
		boolean isFirst = true;
		for (Map.Entry<String, Object> thisInfo : v.getAdditionalInfo().entrySet()) {
			if (!isFirst) buff.append (", ");
			else isFirst = false;
			
			buff.append(thisInfo.getKey())
				.append(":")
				.append(thisInfo.getValue());
		}
		buff.append(" ]");
		return buff.toString();
	}

	private ISnomedBrowserConcept createBrowserConcept(
			BatchImportConcept thisConcept, BatchImportFormat formatter) throws BusinessServiceException {
		SnomedBrowserConcept newConcept = new SnomedBrowserConcept();
		if (!thisConcept.requiresNewSCTID()) {
			newConcept.setConceptId(thisConcept.getSctid());
		}
		newConcept.setActive(true);
		newConcept.setDefinitionStatus(DefinitionStatus.PRIMITIVE);
		
		//Set the Parent
		List<ISnomedBrowserRelationship> relationships = new ArrayList<ISnomedBrowserRelationship>();
		ISnomedBrowserRelationship isA = createRelationship(thisConcept.getSctid(), SCTID_ISA, thisConcept.getParent(), CharacteristicType.STATED_RELATIONSHIP);
		relationships.add(isA);
		newConcept.setRelationships(relationships);
		
		//Add Descriptions FSN, then Preferred Term
		List<ISnomedBrowserDescription> descriptions = new ArrayList<ISnomedBrowserDescription>();
		String prefTerm = thisConcept.get(formatter.getIndex(FIELD.FSN_ROOT));
		String fsnTerm = prefTerm + " (" + thisConcept.get(formatter.getIndex(FIELD.SEMANTIC_TAG)) +")";
		
		ISnomedBrowserDescription fsn = createDescription(fsnTerm, SnomedBrowserDescriptionType.FSN, PREFERRED_ACCEPTABILIY);
		descriptions.add(fsn);
		newConcept.setFsn(fsnTerm);
		
		ISnomedBrowserDescription pref = createDescription(prefTerm, SnomedBrowserDescriptionType.SYNONYM, PREFERRED_ACCEPTABILIY);
		descriptions.add(pref);
		addSynonyms(descriptions, formatter, thisConcept);
		newConcept.setDescriptions(descriptions);
		
		return newConcept;
	}
	
	private void addSynonyms(List<ISnomedBrowserDescription> descriptions,
			BatchImportFormat formatter, BatchImportConcept thisConcept) throws BusinessServiceException {
		List<String> allSynonyms = formatter.getAllSynonyms(thisConcept);
		for (String thisSyn : allSynonyms) {
			if (!containsDescription (descriptions, thisSyn)){
				ISnomedBrowserDescription syn =  createDescription(thisSyn, SnomedBrowserDescriptionType.SYNONYM, ACCEPTABLE_ACCEPTABILIY);
				descriptions.add(syn);
			}
		}
	}

	/**
	 * @param descriptions
	 * @param thisSyn
	 * @return true if the list of descriptions already contains this term
	 */
	private boolean containsDescription(
			List<ISnomedBrowserDescription> descriptions, String term) {
		for (ISnomedBrowserDescription thisDesc : descriptions) {
			if (thisDesc.getTerm().equals(term)) {
				return true;
			}
		}
		return false;
	}

	ISnomedBrowserRelationship createRelationship(String sourceSCTID, String type, String destinationSCTID, CharacteristicType characteristic) {
		SnomedBrowserRelationship rel = new SnomedBrowserRelationship();
		rel.setCharacteristicType(CharacteristicType.STATED_RELATIONSHIP);
		rel.setSourceId(sourceSCTID);
		rel.setType(new SnomedBrowserRelationshipType(SCTID_ISA));
		//Set a temporary id so the user can tell which item failed validation
		rel.setRelationshipId("rel_isa");
		SnomedBrowserRelationshipTarget destination = new SnomedBrowserRelationshipTarget();
		destination.setConceptId(destinationSCTID);
		rel.setTarget(destination);
		rel.setActive(true);

		return rel;
	}
	
	ISnomedBrowserDescription createDescription(String term, SnomedBrowserDescriptionType type, Map<String, Acceptability> acceptabilityMap) {
		SnomedBrowserDescription desc = new SnomedBrowserDescription();
		//Set a temporary id so the user can tell which item failed validation
		desc.setDescriptionId("desc_" + type.toString());
		desc.setTerm(term);
		desc.setActive(true);
		desc.setType(type);
		desc.setLang(SnomedConstants.LanguageCodeReferenceSetIdentifierMapping.EN_LANGUAGE_CODE);
		desc.setAcceptabilityMap(acceptabilityMap);
		desc.setCaseSignificance(CaseSignificance.CASE_INSENSITIVE);
		return desc;
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
	
	public File getImportResultsFile (String projectKey, UUID batchImportId) {
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
		status.setProcessed(status.getProcessed() == null? 1 : status.getProcessed().intValue() + 1);
		if (loaded) {
			status.setLoaded(status.getLoaded() == null? 1 : status.getLoaded().intValue() + 1);
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