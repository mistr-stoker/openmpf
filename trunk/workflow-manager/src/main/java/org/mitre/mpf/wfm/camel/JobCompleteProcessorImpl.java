/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2016 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2016 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.wfm.camel;

import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.mvc.controller.AtmosphereController;
import org.mitre.mpf.mvc.model.JobStatusMessage;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.Redis;
import org.mitre.mpf.wfm.data.RedisImpl;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateJobRequestDaoImpl;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateMarkupResultDaoImpl;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.mitre.mpf.wfm.enums.JobStatus;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.event.JobCompleteNotification;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.event.NotificationConsumer;
import org.mitre.mpf.wfm.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

@Component(JobCompleteProcessorImpl.REF)
public class JobCompleteProcessorImpl extends WfmProcessor implements JobCompleteProcessor {
	private static final Logger log = LoggerFactory.getLogger(JobCompleteProcessor.class);
	public static final String REF = "jobCompleteProcessorImpl";

	private Set<NotificationConsumer<JobCompleteNotification>> consumers = new ConcurrentSkipListSet<>();

	@Autowired
	@Qualifier(HibernateJobRequestDaoImpl.REF)
	private HibernateDao<JobRequest> jobRequestDao;

	@Autowired
	@Qualifier(HibernateMarkupResultDaoImpl.REF)
	private MarkupResultDao markupResultDao;

	@Autowired
	private IoUtils ioUtils;

	@Autowired
	private PropertiesUtil propertiesUtil;

	@Autowired
	private JsonUtils jsonUtils;

	@Autowired
	@Qualifier(RedisImpl.REF)
	private Redis redis;
	
	@Autowired
	private JobProgress jobProgressStore;

	@Override
	public void wfmProcess(Exchange exchange) throws WfmProcessingException {
		Long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
		assert jobId != null : String.format("The header '%s' (value=%s) was not set or is not a Long.", MpfHeaders.JOB_ID, exchange.getIn().getHeader(MpfHeaders.JOB_ID));

		if(jobId == Long.MIN_VALUE) {
			// If we receive a very large negative Job ID, it means an exception was encountered during processing of a job,
			// and none of the provided error handling logic could fix it. Further processing should not be performed.
			log.warn("[Job {}:*:*] An error prevents a job from completing successfully. Please review the logs for additional information.", jobId);
		} else {
			String statusString = exchange.getIn().getHeader(MpfHeaders.JOB_STATUS, String.class);
			Mutable<JobStatus> jobStatus = new MutableObject<>(JobStatus.parse(statusString, JobStatus.UNKNOWN));

			markJobStatus(jobId, jobStatus.getValue());

			try {
				markJobStatus(jobId, JobStatus.BUILDING_OUTPUT_OBJECT);

				// NOTE: jobStatus is mutable - it __may__ be modified in createOutputObject!
				createOutputObject(jobId, jobStatus);
			} catch (Exception exception) {
				log.warn("Failed to create the output object for Job {} due to an exception.", jobId, exception);
				jobStatus.setValue(JobStatus.ERROR);
			}

			markJobStatus(jobId, jobStatus.getValue());

			try {
				callback(jobId);
			} catch (Exception exception) {
				log.warn("Failed to make callback (if appropriate) for Job #{}.", jobId);
			}

			// Tear down the job.
			try {
				destroy(jobId);
			} catch (Exception exception) {
				log.warn("Failed to clean up Job {} due to an exception. Data for this job will remain in the transient data store, but the status of the job has not been affected by this failure.", jobId, exception);
			}

			JobCompleteNotification jobCompleteNotification = new JobCompleteNotification(exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class));

			for (NotificationConsumer<JobCompleteNotification> consumer : consumers) {
				if (!jobCompleteNotification.isConsumed()) {
					try {
						consumer.onNotification(this, new JobCompleteNotification(exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class)));
					} catch (Exception exception) {
						log.warn("Completion consumer '{}' threw an exception.", consumer, exception);
					}
				}
			}

			AtmosphereController.broadcast(new JobStatusMessage(jobId, 100, jobStatus.getValue(), new Date()));
			jobProgressStore.setJobProgress(jobId, 100.0f);
			log.info("[Job {}:*:*] Job complete!", jobId);
		}
	}

	private void callback(long jobId) throws WfmProcessingException {
		final String jsonCallbackURL = redis.getCallbackURL(jobId);
		final String jsonCallbackMethod = redis.getCallbackMethod(jobId);
		if(jsonCallbackURL != null && jsonCallbackMethod != null && (jsonCallbackMethod.equals("POST") || jsonCallbackMethod.equals("GET"))) {
			log.info("Starting "+jsonCallbackMethod+" callback to "+jsonCallbackURL);
			try {
				JsonCallbackBody jsonBody =new JsonCallbackBody(jobId, redis.getExternalId(jobId));
				new Thread(new CallbackThread(jsonCallbackURL,jsonCallbackMethod,jsonBody)).start();
			} catch (IOException ioe) {
				log.warn("Failed to issue {} callback to '{}' due to an I/O exception.",jsonCallbackMethod, jsonCallbackURL, ioe);
			}
		}
	}

	private void markJobStatus(long jobId, JobStatus jobStatus) {
		log.debug("Marking Job {} as '{}'.", jobId, jobStatus);

		JobRequest jobRequest = jobRequestDao.findById(jobId);
		assert jobRequest != null : String.format("A job request entity must exist with the ID %d", jobId);

		jobRequest.setTimeCompleted(new Date());
		jobRequest.setStatus(jobStatus);
		jobRequestDao.persist(jobRequest);
	}

	public void createOutputObject(long jobId, Mutable<JobStatus> jobStatus) throws WfmProcessingException {
		TransientJob transientJob = redis.getJob(jobId);
		JobRequest jobRequest = jobRequestDao.findById(jobId);

		if(transientJob.isCancelled()) {
			jobStatus.setValue(JobStatus.CANCELLED);
		}

		JsonOutputObject jsonOutputObject = new JsonOutputObject(jobRequest.getId(),
				UUID.randomUUID().toString(),
				transientJob == null ? null : jsonUtils.convert(transientJob.getPipeline()),
				transientJob.getPriority(),
				propertiesUtil.getSiteId(),
				transientJob.getExternalId(),
				jobRequest.getTimeReceived().toString(),
				jobRequest.getTimeCompleted().toString(),
				jobStatus.getValue().toString());

		// Build detection output object....
		int mediaIndex = 0;
		for(TransientMedia transientMedia : transientJob.getMedia()) {
			StringBuilder stateKeyBuilder = new StringBuilder("+");

			JsonMediaOutputObject mediaOutputObject = new JsonMediaOutputObject(transientMedia.getId(), transientMedia.getUri(), transientMedia.getType(),
					transientMedia.getLength(), transientMedia.getSha256(), transientMedia.getMessage(),
					transientMedia.isFailed() ? "ERROR" : "COMPLETE");

			mediaOutputObject.getMediaMetadata().putAll(transientMedia.getMetadata());
			mediaOutputObject.getOverriddenProperties().putAll(transientMedia.getMediaSpecificProperties());

			MarkupResult markupResult = markupResultDao.findByJobIdAndMediaIndex(jobId, mediaIndex);
			if(markupResult != null) {
				mediaOutputObject.setMarkupResult(new JsonMarkupOutputObject(markupResult.getId(), markupResult.getMarkupUri(), markupResult.getMarkupStatus().name(), markupResult.getMessage()));
			}

			if(transientJob.getPipeline() != null) {
				for (int stageIndex = 0; stageIndex < transientJob.getPipeline().getStages().size(); stageIndex++) {
					TransientStage transientStage = transientJob.getPipeline().getStages().get(stageIndex);
					for (int actionIndex = 0; actionIndex < transientStage.getActions().size(); actionIndex++) {
						TransientAction transientAction = transientStage.getActions().get(actionIndex);
						String stateKey = String.format("%s#%s", stateKeyBuilder.toString(), transientAction.getName());

						for (DetectionProcessingError detectionProcessingError : redis.getDetectionProcessingErrors(jobId, transientMedia.getId(), stageIndex, actionIndex)) {
							JsonDetectionProcessingError jsonDetectionProcessingError = new JsonDetectionProcessingError(detectionProcessingError.getStartOffset(), detectionProcessingError.getEndOffset(), detectionProcessingError.getError());
							if (!mediaOutputObject.getDetectionProcessingErrors().containsKey(stateKey)) {
								mediaOutputObject.getDetectionProcessingErrors().put(stateKey, new TreeSet<JsonDetectionProcessingError>());
							}
							mediaOutputObject.getDetectionProcessingErrors().get(stateKey).add(jsonDetectionProcessingError);
							if (!StringUtils.equalsIgnoreCase(mediaOutputObject.getStatus(), "COMPLETE")) {
								mediaOutputObject.setStatus("INCOMPLETE");
								if (StringUtils.equalsIgnoreCase(jsonOutputObject.getStatus(), "COMPLETE")) {
									jsonOutputObject.setStatus("INCOMPLETE");
								}
							}
						}

						Collection<Track> tracks = redis.getTracks(jobId, transientMedia.getId(), stageIndex, actionIndex);
						if(tracks.size() == 0) {
							// Always include detection actions in the output object, even if they do not generate any results.
							if (!mediaOutputObject.getTracks().containsKey(stateKey)) {
								mediaOutputObject.getTracks().put(stateKey, new TreeSet<JsonTrackOutputObject>());
							}
						} else {
							for (Track track : tracks) {
								JsonTrackOutputObject jsonTrackOutputObject = new JsonTrackOutputObject(
											TextUtils.getTrackUuid(transientMedia.getSha256(), track.getExemplar().getMediaOffsetFrame(), track.getExemplar().getX(), track.getExemplar().getY(), track.getExemplar().getWidth(), track.getExemplar().getHeight(), track.getType()),
											track.getStartOffsetFrameInclusive(), track.getEndOffsetFrameInclusive(),
										    track.getStartOffsetTimeInclusive(), track.getEndOffsetTimeInclusive(), track.getType(), stateKey);


								jsonTrackOutputObject.setExemplar(new JsonDetectionOutputObject(track.getExemplar().getX(), track.getExemplar().getY(), track.getExemplar().getWidth(), track.getExemplar().getHeight(), track.getExemplar().getConfidence(), track.getExemplar().getDetectionProperties(), track.getExemplar().getMediaOffsetFrame(), track.getExemplar().getMediaOffsetTime(), track.getExemplar().getArtifactExtractionStatus().name(), track.getExemplar().getArtifactPath()));
								for (Detection detection : track.getDetections()) {
									jsonTrackOutputObject.getDetections().add(new JsonDetectionOutputObject(detection.getX(), detection.getY(), detection.getWidth(), detection.getHeight(), detection.getConfidence(), detection.getDetectionProperties(), detection.getMediaOffsetFrame(), detection.getMediaOffsetTime(), detection.getArtifactExtractionStatus().name(), detection.getArtifactPath()));

								}

								if (!mediaOutputObject.getTracks().containsKey(stateKey)) {
									mediaOutputObject.getTracks().put(stateKey, new TreeSet<JsonTrackOutputObject>());
								}
								mediaOutputObject.getTracks().get(stateKey).add(jsonTrackOutputObject);
							}
						}

						if (actionIndex == transientStage.getActions().size() - 1) {
							stateKeyBuilder.append("#").append(transientAction.getName());
						}
					}
				}
			}
			jsonOutputObject.getMedia().add(mediaOutputObject);
			mediaIndex++;
		}

		try {
			File outputFile = propertiesUtil.createDetectionOutputObjectFile(jobId);
			jsonUtils.serialize(jsonOutputObject, outputFile);
			jobRequest.setOutputObjectPath(outputFile.getAbsolutePath());
			jobRequestDao.persist(jobRequest);
		} catch(IOException | WfmProcessingException wpe) {
			log.error("Failed to create the JSON detection output object for '{}' due to an exception.", jobId, wpe);
		}

		try {
			jmsUtils.destroyCancellationRoutes(jobId);
		} catch (Exception exception) {
			log.warn("Failed to destroy the cancellation routes associated with {}. If this job is resubmitted, it will likely not complete again!", jobId, exception);
		}

	}

	@Autowired
	private JmsUtils jmsUtils;

	private void destroy(long jobId) throws WfmProcessingException {
		TransientJob transientJob = redis.getJob(jobId);
		for(TransientMedia transientMedia : transientJob.getMedia()) {
			if(transientMedia.getUriScheme().isRemote() && transientMedia.getLocalPath() != null) {
				try {
					Files.deleteIfExists(Paths.get(transientMedia.getLocalPath()));
				} catch(Exception exception) {
					log.warn("[{}|*|*] Failed to delete locally cached file '{}' due to an exception. This file must be manually deleted.", transientJob.getId(), transientMedia.getLocalPath());
				}
			}
		}
		redis.clearJob(jobId);
	}

	@Override
	public void subscribe(NotificationConsumer<JobCompleteNotification> consumer) {
		consumers.add(consumer);
	}

	@Override
	public void unsubscribe(NotificationConsumer<JobCompleteNotification> consumer) {
		consumers.remove(consumer);
	}

	/**
	 * Thread to handle the Callback to a URL given a HTTP method
	 */
	public class CallbackThread implements Runnable {
		private String callbackURL;
		private String callbackMethod;
		private HttpUriRequest req;

		public CallbackThread(String callbackURL,String callbackMethod,JsonCallbackBody body) throws UnsupportedEncodingException {
			this.callbackURL = callbackURL;
			this.callbackMethod = callbackMethod;

			if(callbackMethod.equals("GET")) {
				String jsonCallbackURL2 = callbackURL;
				if(jsonCallbackURL2.contains("?")){
					jsonCallbackURL2 +="&";
				}else{
					jsonCallbackURL2 +="?";
				}
				jsonCallbackURL2 +="jobid="+body.getJobId();
				if(body.getExternalId() != null){
					jsonCallbackURL2 += "&externalid="+body.getExternalId();
				}
				req = new HttpGet(jsonCallbackURL2);
			}else { // this is for a POST
				HttpPost post = new HttpPost(callbackURL);
				post.addHeader("Content-Type", "application/json");				;
				try {
					post.setEntity(new StringEntity(jsonUtils.serializeAsTextString(body)));
					req = post;
				} catch (WfmProcessingException e) {
					log.error("Cannont serialize CallbackBody");
				}
			}
		}

		@Override
		public void run() {
			final HttpClient httpClient = HttpClientBuilder.create().build();
			try {
				HttpResponse response = httpClient.execute(req);
				log.info("{} Callback issued to '{}' (Response={}).", callbackMethod, callbackURL,response);
			} catch (Exception exception) {
				log.warn("Failed to issue {} callback to '{}' due to an I/O exception.", callbackMethod, callbackURL, exception);
			}
		}
	}
}
