package org.ihtsdo.snowowl.authoring.batchimport.api.rest;

import io.swagger.annotations.*;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClient;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.snowowl.authoring.batchimport.api.client.AuthoringServicesClient;
import org.ihtsdo.snowowl.authoring.batchimport.api.pojo.batch.BatchImportRequest;
import org.ihtsdo.snowowl.authoring.batchimport.api.pojo.batch.BatchImportStatus;
import org.ihtsdo.snowowl.authoring.batchimport.api.service.BatchImportFormat;
import org.ihtsdo.snowowl.authoring.batchimport.api.service.BatchImportService;
import org.ihtsdo.snowowl.authoring.single.api.rest.ControllerHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static java.util.UUID.randomUUID;

@Api("Batch Import")
@RestController
@RequestMapping(produces={MediaType.APPLICATION_JSON_VALUE})
public class BatchImportController {

	@Autowired
	private BatchImportService batchImportService;
	
	@Value("+{batch.import.snowowl.url}")
	private String snowOwlUrl;
	
	@Value("+{batch.import.authoring-services.url}")
	private String authoringServicesUrl;
	
	@Value("+{batch.import.sso.cookie}")
	private String cookieName;

	@ApiOperation(value="Import 3rd Party Concept file eg SIRS")
	@ApiResponses({
		@ApiResponse(code = 201, message = "Created"),
		@ApiResponse(code = 404, message = "Task not found"),
	})
	@RequestMapping(value="/projects/{projectKey}/batchImport", method= RequestMethod.POST)
	public void startBatchImport(@PathVariable final String projectKey,
			@RequestParam("createForAuthor") final String createForAuthor,
			@RequestParam("conceptsPerTask") final Integer conceptsPerTask,
			
			@ApiParam(value="seconds to delay after creating task")
			@RequestParam("postTaskDelay") final Integer postTaskDelay,
			@RequestParam("dryRun") final Boolean dryRun,
			@RequestParam(value ="allowLateralizedContent", defaultValue = "FALSE") final Boolean allowLateralizedContent,
			
			@ApiParam(value="3rd Party import csv file")
			@RequestPart("file") 
			final MultipartFile file,
			HttpServletRequest request,
			HttpServletResponse response ) throws BusinessServiceException {
		
		try {
			final UUID batchImportId = randomUUID();
			
			Reader in = new InputStreamReader(file.getInputStream());
			//SIRS files contain duplicate headers (eg multiple Notes columns) 
			//So read 1st row as a record instead.
			CSVParser parser = CSVFormat.EXCEL.parse(in);
			CSVRecord header = parser.iterator().next();
			BatchImportFormat format = BatchImportFormat.determineFormat(header);
			//And load the remaining records into memory
			List<CSVRecord> rows = parser.getRecords();
			
			//Swagger has difficulty handling both json and file in the same endpoint
			//So we'll pass the items we need as individual parameters 
			BatchImportRequest importRequest = new BatchImportRequest();
			importRequest.setCreateForAuthor(createForAuthor.toLowerCase());
			importRequest.setConceptsPerTask(conceptsPerTask == null? 1 : conceptsPerTask.intValue());
			importRequest.setFormat(format);
			importRequest.setProjectKey(projectKey);
			importRequest.setOriginalFilename(file.getOriginalFilename());
			importRequest.setPostTaskDelay(postTaskDelay);
			importRequest.setDryRun(dryRun);
			importRequest.allowLateralizedContent(allowLateralizedContent);
			parser.close();
			String ssoCookie = getSsoCookie(request);
			batchImportService.startImport(batchImportId, importRequest, rows, ControllerHelper.getUsername(), getASClient(ssoCookie), getSOClient(ssoCookie));
			response.setHeader("Location", request.getRequestURL() + "/" + batchImportId.toString());
		} catch (Exception e) {
			throw new BusinessServiceException ("Unable to import batch file",e);
		}
	}
	
	private AuthoringServicesClient getASClient(String ssoCookie) throws Exception {
		return new AuthoringServicesClient(authoringServicesUrl, ssoCookie);
	}
	
	private SnowOwlRestClient getSOClient(String ssoCookie) throws Exception {
		
		return new SnowOwlRestClient(snowOwlUrl, ssoCookie);
	}

	private String getSsoCookie(HttpServletRequest request) throws Exception {
		String ssoCookie = null;
		for (Cookie cookie : request.getCookies()) {
			if (cookie.getName().equals(cookieName)) {
				ssoCookie = cookie.getName() + "=" + cookie.getValue();
				break;
 			}
		}
		if (ssoCookie == null) {
			throw new Exception ("Unable to recover sso cookie '" + cookieName + "' from request - not found.");
		}
		return ssoCookie;
	}

	@ApiOperation( 
			value="Retrieve import run status", 
			notes="Returns the specified batch import run's status.")
	@ApiResponses({
		@ApiResponse(code = 200, message = "OK"),
		@ApiResponse(code = 404, message = "Batch import not found"),
	})
	@RequestMapping(value="/projects/{projectKey}/batchImport/{batchImportId}", method=RequestMethod.GET)
	public BatchImportStatus getBatchImportStatus(
			@PathVariable final String projectKey,
			@ApiParam(value="The batch import identifier")
			@PathVariable(value="batchImportId") 
			final UUID batchImportId,
			HttpServletResponse request,
			HttpServletResponse response) {

		return batchImportService.getImportStatus(batchImportId);
	}
	
	@ApiOperation(
			value="Retrieve import run details", 
			notes="Returns the specified import run's results on a per row basis as a CSV file.")
	@ApiResponses({
		@ApiResponse(code = 200, message = "OK"),
		@ApiResponse(code = 404, message = "Batch import not found"),
	})
	@RequestMapping(value="/projects/{projectKey}/batchImport/{batchImportId}/results", method=RequestMethod.GET)
	public void getBatchImportResults(
			@PathVariable final String projectKey,
			@ApiParam(value="The import identifier")
			@PathVariable(value="batchImportId") 
			final UUID batchImportId,
			HttpServletResponse request,
			HttpServletResponse response) throws BusinessServiceException {

		String csvFileName = "results_" + batchImportService.getImportResultsFile(projectKey, batchImportId).getName();
		response.setContentType("text/csv");

		String headerKey = "Content-Disposition";
		String headerValue = String.format("attachment; filename=\"%s\"", csvFileName);
		response.setHeader(headerKey, headerValue);
		try {
			Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
			String fileContent = batchImportService.getImportResults(projectKey, batchImportId);
			writer.append(fileContent);
			writer.flush();
			writer.close();
		} catch (IOException e) {
			throw new BusinessServiceException ("Unable to recover batch import results",e);
		} 
	}

}