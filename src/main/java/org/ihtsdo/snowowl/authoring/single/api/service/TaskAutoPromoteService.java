package org.ihtsdo.snowowl.authoring.single.api.service;

import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.otf.rest.client.snowowl.PathHelper;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClient;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClientFactory;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ApiError;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ClassificationResults;
import org.ihtsdo.otf.rest.client.snowowl.pojo.Merge;
import org.ihtsdo.otf.rest.client.snowowl.pojo.MergeReviewsResults;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.snowowl.authoring.single.api.pojo.*;
import org.ihtsdo.snowowl.authoring.single.api.rest.ControllerHelper;
import org.ihtsdo.snowowl.authoring.single.api.review.pojo.BranchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import us.monoid.json.JSONException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.ihtsdo.otf.rest.client.snowowl.pojo.MergeReviewsResults.MergeReviewStatus.CURRENT;

public class TaskAutoPromoteService {

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private SnowOwlRestClientFactory snowOwlRestClientFactory;

	@Autowired
	private TaskService taskService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private ValidationService validationService;

	@Autowired
	private ClassificationService classificationService;

	private final LinkedBlockingQueue<AutomatePromoteProcess> autoPromoteBlockingQueue = new LinkedBlockingQueue<AutomatePromoteProcess>();
	private final Map<String, ProcessStatus> autoPromoteStatus;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public TaskAutoPromoteService() {
		autoPromoteStatus = new HashMap<>();
	}

	@Scheduled(initialDelay = 60000, fixedDelay = 5000)
	private void processAutoPromotionJobs() {
		try {
			AutomatePromoteProcess automatePromoteProcess = autoPromoteBlockingQueue.take();
			doAutoPromoteTaskToProject(automatePromoteProcess.getProjectKey(), automatePromoteProcess.getTaskKey(), automatePromoteProcess.getAuthentication());
		} catch (InterruptedException e) {
			logger.warn("Failed to take task auto-promotion job from the queue.", e);
		}
	}

	public void autoPromoteTaskToProject(String projectKey, String taskKey) throws BusinessServiceException {
		AutomatePromoteProcess automatePromoteProcess = new AutomatePromoteProcess(SecurityContextHolder.getContext().getAuthentication(), projectKey, taskKey);
		try {
			autoPromoteStatus.put(getAutoPromoteStatusKey(automatePromoteProcess.getProjectKey(), automatePromoteProcess.getTaskKey()), new ProcessStatus("Queued", ""));
			autoPromoteBlockingQueue.put(automatePromoteProcess);
		} catch (InterruptedException e) {
			autoPromoteStatus.put(getAutoPromoteStatusKey(automatePromoteProcess.getProjectKey(), automatePromoteProcess.getTaskKey()), new ProcessStatus("Failed", e.getMessage()));
		}
	}

	private synchronized void doAutoPromoteTaskToProject(String projectKey, String taskKey, Authentication authentication){
		SecurityContextHolder.getContext().setAuthentication(authentication);
		try {
			// Call rebase process
			Merge merge = new Merge();
			String mergeId = this.autoRebaseTask(projectKey, taskKey);

			if (null != mergeId && mergeId.equals("stopped")) {
				return;
			}

			// Call classification process
			Classification classification =  this.autoClassificationTask(projectKey, taskKey);
			if(null != classification && (classification.getResults().getRelationshipChangesCount() == 0)) {

				// Call promote process
				merge = this.autoPromoteTask(projectKey, taskKey, mergeId);
				if (merge.getStatus() == Merge.Status.COMPLETED) {
					notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.BranchState, "Success to auto promote task"));
					autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Completed", ""));
					taskService.stateTransition(projectKey, taskKey, TaskStatus.PROMOTED);
				} else {
					autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Failed", merge.getApiError().getMessage()));
				}
			}
		} catch (BusinessServiceException e) {
			autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Failed", e.getMessage()));
		} finally {
			SecurityContextHolder.getContext().setAuthentication(null);
		}
	}

	private Merge autoPromoteTask(String projectKey, String taskKey, String mergeId) throws BusinessServiceException {
		notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Classification, "Running promote authoring task"));
		autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Promoting", ""));
		String taskBranchPath = taskService.getTaskBranchPathUsingCache(projectKey, taskKey);
		return branchService.mergeBranchSync(taskBranchPath, PathHelper.getParentPath(taskBranchPath), mergeId);
	}

	private org.ihtsdo.snowowl.authoring.single.api.pojo.Status autoValidateTask(String projectKey, String taskKey) throws BusinessServiceException {
		Status status = validationService.startValidation(projectKey, taskKey, ControllerHelper.getUsername());
		if(status == null && !status.equals("COMPLETED")) { // TODO: This will produce a null pointer - also status is not a string so equals will not work
			return null;
		}
		return status;
	}

	private Classification autoClassificationTask(String projectKey, String taskKey) throws BusinessServiceException {
		notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Classification, "Running classification authoring task"));
		autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Classifying", ""));
		String branchPath = taskService.getTaskBranchPathUsingCache(projectKey, taskKey);
		try {
			Classification classification =  classificationService.startClassification(projectKey, taskKey, branchPath, ControllerHelper.getUsername());

			snowOwlRestClientFactory.getClient().waitForClassificationToComplete(classification.getResults());

			if (classification.getResults().getStatus().equals(ClassificationResults.ClassificationStatus.COMPLETED.toString())) {
				if (null != classification && null != classification.getResults() && classification.getResults().getRelationshipChangesCount() != 0) {
					autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Classified with results", ""));
				}
			} else {
				throw new BusinessServiceException(classification.getMessage());
			}
			return classification;
		} catch (RestClientException | JSONException | InterruptedException e) {
			notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Classification, "Failed to start classification."));
			throw new BusinessServiceException("Failed to classify", e);
		}
	}

	private String autoRebaseTask(String projectKey, String taskKey) throws BusinessServiceException {

		notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Rebase, "Running auto rebase authoring task"));
		autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Rebasing", ""));
		String taskBranchPath = taskService.getTaskBranchPathUsingCache(projectKey, taskKey);

		// Get current task and check branch state
		AuthoringTask authoringTask = taskService.retrieveTask(projectKey, taskKey);
		String branchState = authoringTask.getBranchState();
		if (null != authoringTask) {// TODO: if authoring task is null the line above will throw a null pointer

			// Will skip rebase process if the branch state is FORWARD or UP_TO_DATE
			if ((branchState.equalsIgnoreCase(BranchState.FORWARD.toString()) || branchState.equalsIgnoreCase(BranchState.UP_TO_DATE.toString())) ) {
				return null;
			} else {
				try {
					SnowOwlRestClient client = snowOwlRestClientFactory.getClient();
					String mergeId = client.createBranchMergeReviews(PathHelper.getParentPath(taskBranchPath), taskBranchPath);
					MergeReviewsResults mergeReview;
					int sleepSeconds = 4;
					int totalWait = 0;
					int maxTotalWait = 60 * 60;
					try {
						do {
							Thread.sleep(1000 * sleepSeconds);
							totalWait += sleepSeconds;
							mergeReview = client.getMergeReviewsResult(mergeId);
							if (sleepSeconds < 10) {
								sleepSeconds+=2;
							}
						} while (totalWait < maxTotalWait && (mergeReview.getStatus() != CURRENT));

						// Check conflict of merge review
						if (client.isNoMergeConflict(mergeId)) {

							// Process rebase task
							Merge merge = branchService.mergeBranchSync(PathHelper.getParentPath(taskBranchPath), taskBranchPath, null);
							if (merge.getStatus() == Merge.Status.COMPLETED) {
								return mergeId;
							} else {
								ApiError apiError = merge.getApiError();
								String message = apiError != null ? apiError.getMessage() : null;
								notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Rebase, message));
								autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Rebased with conflicts", message));
								return "stopped";
							}
						} else {
							notificationService.queueNotification(ControllerHelper.getUsername(), new Notification(projectKey, taskKey, EntityType.Rebase, "Rebase has conflicts"));
							autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Rebased with conflicts", ""));
							return "stopped";
						}
					} catch (InterruptedException | RestClientException e) {
						autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("Rebased with conflicts", e.getMessage()));
						throw new BusinessServiceException("Failed to fetch merge reviews status.", e);
					}
				} catch (RestClientException e) {
					autoPromoteStatus.put(getAutoPromoteStatusKey(projectKey, taskKey), new ProcessStatus("e.getMessage()", e.getMessage()));
					throw new BusinessServiceException("Failed to start merge reviews.", e);
				}

			}
		}
		return null;
	}

	public ProcessStatus getAutoPromoteStatus(String projectKey, String taskKey) {
		return autoPromoteStatus.get(getAutoPromoteStatusKey(projectKey, taskKey));
	}

	private String getAutoPromoteStatusKey(String projectKey, String taskKey) {
		return projectKey + "|" + taskKey;
	}

}
