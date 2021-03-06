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

package org.mitre.mpf.mvc.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.*;
import org.apache.commons.io.IOUtils;
import org.mitre.mpf.interop.JsonJobRequest;
import org.mitre.mpf.interop.JsonMediaInputObject;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.mvc.model.SessionModel;
import org.mitre.mpf.rest.api.*;
import org.mitre.mpf.mvc.util.ModelUtils;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.enums.JobStatus;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.service.MpfService;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

// swagger includes

@Api( value = "Jobs",
	description = "Job status, cancel, resubmit, output")
@Controller
@Scope("request")
@Profile("website")
public class JobController
{
	private static final Logger log = LoggerFactory.getLogger(JobController.class);

	public static final String DEFAULT_ERROR_VIEW = "error";

	@Autowired
	private PropertiesUtil propertiesUtil;

	@Autowired //will grab the impl
	private MpfService mpfService;

	@Autowired
	private SessionModel sessionModel;

	@Autowired
	private JobProgress jobProgress;
	
	/*
	 *	POST /jobs
	 */
	//EXTERNAL
	@RequestMapping(value = { "/rest/jobs" }, method = RequestMethod.POST)
	@ApiOperation(value="Creates and submits a job using a JSON JobCreationRequest object as the request body.",
		notes = "The pipelineName should be one of the values in 'rest/pipelines'. For example: http://localhost/images/image.png. Another example: file:///home/user/images/image.jpg."+
			" A callbackURL (optional) and callbackMethod (GET or POST) may be added. When the job completes, the callback will perform a GET or POST to the callbackURL with "+
				"the parameters 'jobId' and 'externalId' of the completed job." +
				" For example, on a GET to a callbackURL: /api.example.com/foo?jobId=1&externalId=1. Another example: /api.example.com/foo?someparam=something&jobId=1&externalId=1."+
			" Example when no externalId is provided: /api.example.com/foo?jobId=1. The body of a POST callback will always include the 'jobId' and 'externalId', even if the latter is 'null'.",
		produces="application/json", response=JobCreationResponse.class)
    @ApiResponses(value = { 
    		@ApiResponse(code = 201, message = "Job created"), 
    		@ApiResponse(code = 401, message = "Bad credentials") })
	@ResponseBody
	@ResponseStatus(value = HttpStatus.CREATED) //return 201 for successful post
	public JobCreationResponse processMediaRest(@ApiParam(required = true, value="JobCreationRequest") @RequestBody JobCreationRequest jobCreationRequest) {
		return processMediaVersionOne(jobCreationRequest);
	}
	
	//INTERNAL
	@RequestMapping(value = { "/jobs" }, method = RequestMethod.POST)
	@ResponseBody
	@ResponseStatus(value = HttpStatus.CREATED) //return 201 for successful post
	public JobCreationResponse processMediaSession(@RequestBody JobCreationRequest jobCreationRequest) {
		return processMediaVersionOne(jobCreationRequest);
	}
	
	/*
	 * GET /jobs
	 */
	//INTERNAL
	@RequestMapping(value = "/jobs", method = RequestMethod.GET)
	@ResponseBody
    public List<SingleJobInfo> getJobStatusSession(@RequestParam(value="useSession", required = false) boolean useSession) {
		return getJobStatusVersionOne(null, useSession);
    }


	@RequestMapping(value = { "/jobs-paged" }, method = RequestMethod.POST)
	@ResponseBody
	public String getJobStatusSessionFiltered(@RequestParam(value="useSession", required = false) boolean useSession,
									  @RequestParam(value="draw", required=false) int draw,
									  @RequestParam(value="start", required=false) int start,
									  @RequestParam(value="length", required=false) int length,
									  @RequestParam(value="search", required=false) String search,
									  @RequestParam(value="sort", required=false) String sort){
		log.debug("Params useSession:{} draw:{} start:{},length:{},search:{},sort:{} ",useSession,draw,start,length,search,sort);

		List<SingleJobInfo> jobInfoModels = getJobStatusVersionOne(null, useSession);
		Collections.reverse(jobInfoModels);//newest first

		//handle search
		if(search != null && search.length() > 0) {
			search = search.toLowerCase();
			List<SingleJobInfo> search_results = new ArrayList<SingleJobInfo>();
			for (int i = 0; i < jobInfoModels.size(); i++) {
				SingleJobInfo info = jobInfoModels.get(i);
				DateFormat df = new SimpleDateFormat("MM/dd/yyyy hh:mm a");
				//apply search to id,pipeline name, status, dates
				if (info.getJobId().toString().contains(search) ||
						info.getPipelineName().toLowerCase().contains(search) ||
						info.getJobStatus().toLowerCase().contains(search) ||
						(info.getEndDate() != null && df.format(info.getEndDate()).toLowerCase().contains(search)) ||
						(info.getStartDate() != null && df.format(info.getStartDate()).toLowerCase().contains(search))) {
					search_results.add(info);
				}
			}
			jobInfoModels=search_results;
		}

		int records_total = jobInfoModels.size();
		int records_filtered = records_total;// Total records, after filtering (i.e. the total number of records after filtering has been applied - not just the number of records being returned for this page of data).

		//handle paging
		int end = start + length;
		end = (end > records_total)? records_total : end;
		start = (start<=end)? start : end;
		List<SingleJobInfo> jobInfoModelsFiltered = jobInfoModels.subList(start,end);

		//build json
		String error=null;
		String json ="[]";
		ObjectMapper jsonMapper = new ObjectMapper();
		try {
			json= jsonMapper.writeValueAsString(jobInfoModelsFiltered);
		} catch (JsonProcessingException e) {
			error = "Error converting Jobs List to JSON : " +e.getMessage();
			log.error(error);
		}
		return "{\"draw\":"+draw+ ",\"recordsTotal\":"+records_total+",\"recordsFiltered\":"+records_filtered+",\"error\":"+error+",\"data\":"+json+"}";
	}
	/*
	 * GET /jobs/{id}
	 */
	//EXTERNAL
	@RequestMapping(value = "/rest/jobs/{id}", method = RequestMethod.GET)
	@ApiOperation(value="Gets a SingleJobInfo model for the job with the id provided as a path variable.", 
		produces="application/json", response=SingleJobInfo.class)
    @ApiResponses(value = { 
    		@ApiResponse(code = 200, message = "Successful response"),
    		@ApiResponse(code = 400, message = "Invalid id"),
    		@ApiResponse(code = 401, message = "Bad credentials") })
	@ResponseBody
    public ResponseEntity<SingleJobInfo> getJobStatusRest(/* @ApiParam(value="The version of this request - NOT IMPLEMENTED - NOT REQUIRED") 
    		@RequestParam(value = "v", required = false) String v, */
    		@ApiParam(required = true, value="Job Id") @PathVariable("id") long jobIdPathVar) {
		SingleJobInfo singleJobInfoModel = null;
		List<SingleJobInfo> jobInfoModels = getJobStatusVersionOne(jobIdPathVar, false);
		if(jobInfoModels != null && jobInfoModels.size() == 1) {
			singleJobInfoModel = jobInfoModels.get(0);
		} else {
			log.error("Error retrieving the SingleJobInfo model for the job with id '{}'", jobIdPathVar);
		}
		
		//return 200 for successful GET and object, 401 for bad credentials, 400 for bad id
		return new ResponseEntity<>(singleJobInfoModel, (singleJobInfoModel != null) ? HttpStatus.OK : HttpStatus.BAD_REQUEST);
    }	
	//INTERNAL
	@RequestMapping(value = "/jobs/{id}", method = RequestMethod.GET)
	@ResponseBody
    public SingleJobInfo getJobStatusWithIdSession(@PathVariable("id") long jobIdPathVar, 
    		@RequestParam(value="useSession", required = false) boolean useSession) {
		List<SingleJobInfo> jobInfoModels = getJobStatusVersionOne(jobIdPathVar, false);
		if(jobInfoModels != null && jobInfoModels.size() == 1) {
			return jobInfoModels.get(0);
		}
		log.error("Error retrieving the SingleJobInfo model for the job with id '{}'", jobIdPathVar);
		return null;
    }	
	
	/*
	 * /jobs/{id}/output/detection
	 */
	//EXTERNAL
	@RequestMapping(value = "/rest/jobs/{id}/output/detection", method = RequestMethod.GET)
	@ApiOperation(value="Gets the JSON detection output object of a specific job using the job id as a required path variable.", 
		produces="application/json", response=JsonOutputObject.class)
    @ApiResponses(value = { 
    		@ApiResponse(code = 200, message = "Successful response"),
    		@ApiResponse(code = 400, message = "Invalid id"),
    		@ApiResponse(code = 401, message = "Bad credentials") })
	@ResponseBody
    public ResponseEntity<JsonOutputObject> getSerializedDetectionOutputRest(@ApiParam(required = true, value="Job id") @PathVariable("id") long idPathVar) {
		JsonOutputObject jsonOutputObject = null;
		JobRequest jobRequest = mpfService.getJobRequest(idPathVar);
		if(jobRequest != null) {
			jsonOutputObject = jsonPathToJsonNode(jobRequest.getOutputObjectPath(), "detection", JsonOutputObject.class);
		}
				
		//return 200 for successful GET and object, 401 for bad credentials, 400 for bad id
		return new ResponseEntity<>(jsonOutputObject, (jsonOutputObject != null) ? HttpStatus.OK : HttpStatus.BAD_REQUEST);
    }

	
	//INTERNAL
	@RequestMapping(value = "/jobs/output-object", method = RequestMethod.GET)

    public ModelAndView getOutputObject(@RequestParam(value = "id", required = true) long idParam, HttpServletRequest httpServletRequest) throws JsonProcessingException {
		ModelAndView mav = new ModelAndView("output_object");
		
		Object jsonOutputObject = null;

					
		JobRequest jobRequest = mpfService.getJobRequest(idParam);
		if(jobRequest != null) {
			jsonOutputObject = (JsonOutputObject) jsonPathToJsonNode(jobRequest.getOutputObjectPath(), "output", JsonOutputObject.class);
		}		
		
		ObjectMapper mapper = new ObjectMapper();
		//convert to a json string
		String jsonStr = mapper.writeValueAsString(jsonOutputObject);
		
		mav.addObject("jsonObj", jsonStr);
		return mav;
	}
	
	/*
	 * /jobs/{id}/resubmit
	 */
	//EXTERNAL
	@RequestMapping(value = "/rest/jobs/{id}/resubmit", method = RequestMethod.POST)
	@ApiOperation(value="Resubmits the job with the provided job id. If the job priority parameter is not set the default value will be used.", 
	produces="application/json", response=JobCreationResponse.class)
	@ApiResponses(value = { 
		@ApiResponse(code = 200, message = "Successful resubmission request"),
		@ApiResponse(code = 400, message = "Invalid id"),
		@ApiResponse(code = 401, message = "Bad credentials") })
	@ResponseBody
	@ResponseStatus(value = HttpStatus.OK) //return 200 for post in this case
	public ResponseEntity<JobCreationResponse> resubmitJobRest(@ApiParam(required = true, value="Job id") @PathVariable("id") long jobIdPathVar,
			@ApiParam(value="Job priority (0-9 with 0 being the lowest) - OPTIONAL") @RequestParam(value = "jobPriority", required = false) Integer jobPriorityParam)  {
		JobCreationResponse jobCreationResponse = resubmitJobVersionOne(jobIdPathVar, jobPriorityParam);
		
		//return 200 for successful GET and object, 401 for bad credentials, 400 for bad id
		//	job id will be -1 in the jobCreationResponse if there was an error cancelling the job
		return new ResponseEntity<>(jobCreationResponse, (jobCreationResponse.getJobId() == -1) ? HttpStatus.BAD_REQUEST : HttpStatus.OK);
	}
	
	//INTERNAL
	@RequestMapping(value = "/jobs/{id}/resubmit", method = RequestMethod.POST)
	@ResponseBody
	@ResponseStatus(value = HttpStatus.OK) //return 200 for post in this case
	public JobCreationResponse resubmitJobSession(@PathVariable("id") long jobIdPathVar, 
			@RequestParam(value = "jobPriority", required = false) Integer jobPriorityParam)  {
		JobCreationResponse response = resubmitJobVersionOne(jobIdPathVar, jobPriorityParam);
		addJobToSession(response.getJobId());
		return response;
	}

	/*
	 * /jobs/{id}/cancel
	 */
	//EXTERNAL
	@RequestMapping(value = "/rest/jobs/{id}/cancel", method = RequestMethod.POST)
	@ApiOperation(value="Cancels the job with the supplied job id.", 
	produces="application/json", response=MpfResponse.class)
	@ApiResponses(value = { 
			@ApiResponse(code = 200, message = "Successful cancellation attempt"),
			@ApiResponse(code = 400, message = "Invalid id"),
			@ApiResponse(code = 401, message = "Bad credentials") })
	@ResponseBody
	@ResponseStatus(value = HttpStatus.OK) //return 200 for post in this case
	public ResponseEntity<MpfResponse> cancelJobRest(@ApiParam(required = true, value="Job id") @PathVariable("id") long jobId)  {
		MpfResponse mpfResponse = cancelJobVersionOne(jobId);
		
		//return 200 for successful GET and object, 401 for bad credentials, 400 for bad id
		return new ResponseEntity<>(mpfResponse, (mpfResponse.getResponseCode() == 0) ? HttpStatus.OK : HttpStatus.BAD_REQUEST);
	}
	
	//INTERNAL
	@RequestMapping(value = "/jobs/{id}/cancel", method = RequestMethod.POST)
	@ResponseBody
	@ResponseStatus(value = HttpStatus.OK) //return 200 for post in this case
	public MpfResponse cancelJobSession(@PathVariable("id") long jobId)  {
		return cancelJobVersionOne(jobId);
	}
	
	/*
	 * Private methods
	 */
	private JobCreationResponse processMediaVersionOne(JobCreationRequest jobCreationRequest) {
		try {
			boolean fromExternalRestClient = true;
			//hack of using 'from_mpf_web_app' as the externalId to prevent duplicating a method and keeping jobs
			//from the web app in the session jobs collections
			if(jobCreationRequest.getExternalId() != null && jobCreationRequest.getExternalId().equals("from_mpf_web_app")) {
				fromExternalRestClient  = false;
				jobCreationRequest.setExternalId(null);
			}
			boolean buildOutput = propertiesUtil.isOutputObjectsEnabled();
			if(jobCreationRequest.getBuildOutput() != null){
				buildOutput = jobCreationRequest.getBuildOutput();
			}

			int priority = propertiesUtil.getJmsPriority();
			if(jobCreationRequest.getPriority() != null) {
				priority = jobCreationRequest.getPriority();
			}

			JsonJobRequest jsonJobRequest;
			List<JsonMediaInputObject> media = new ArrayList<>();
			for (JobCreationMediaData mediaRequest : jobCreationRequest.getMedia()) {
				JsonMediaInputObject medium = new JsonMediaInputObject(mediaRequest.getMediaUri());
				medium.getProperties().putAll(mediaRequest.getProperties());
				media.add(medium);
			}
			if(jobCreationRequest.getCallbackURL() != null && jobCreationRequest.getCallbackURL().length() > 0){
				jsonJobRequest = mpfService.createJob(media,
						jobCreationRequest.getPipelineName(),
						jobCreationRequest.getExternalId(), //TODO: what do we do with this from the UI?
						buildOutput, // Use the buildOutput value if it is provided, otherwise use the default value from the properties file.,
						priority,// Use the priority value if it is provided, otherwise use the default value from the properties file.
						jobCreationRequest.getCallbackURL(),
						jobCreationRequest.getCallbackMethod());

			}else {
				jsonJobRequest = mpfService.createJob(media,
						jobCreationRequest.getPipelineName(),
						jobCreationRequest.getExternalId(), //TODO: what do we do with this from the UI?
						buildOutput, // Use the buildOutput value if it is provided, otherwise use the default value from the properties file.,
						priority ); // Use the priority value if it is provided, otherwise use the default value from the properties file.);
			}
			long jobId = mpfService.submitJob(jsonJobRequest);
			log.debug("Successful creation of JobId: {}", jobId);
			
			if(!fromExternalRestClient) {
				addJobToSession(jobId);
			}
			
			return new JobCreationResponse(jobId);		
		} catch(Exception ex) { //exception handling - can't throw exception - currently an html page will be returned
			log.error("Failure creating job due to an exception.", ex);
			return new JobCreationResponse(-1, String.format("Failure creating job with External Id '%s' due to an exception. Please check server logs for more detail.", jobCreationRequest.getExternalId()));		
		}
	}

	private void addJobToSession(long jobId) {
		JobRequest submittedJobRequest = mpfService.getJobRequest(jobId);
        boolean isComplete = submittedJobRequest.getStatus() == JobStatus.COMPLETE;
		sessionModel.getSessionJobsMap().put(submittedJobRequest.getId(), isComplete);

	}

	private List<SingleJobInfo> getJobStatusVersionOne(Long jobId, boolean useSession) {
		List<SingleJobInfo> jobInfoList  = new ArrayList<SingleJobInfo>();
		try {
			List<JobRequest> jobs = new ArrayList<JobRequest>();
			if(jobId != null) {
				JobRequest job = mpfService.getJobRequest(jobId);
				if(job != null) {
					jobs.add(job);
				}			
			} else {
				if (useSession) {
					for (Long keyId : sessionModel.getSessionJobsMap().keySet()) {
						jobs.add(mpfService.getJobRequest(keyId));
					}
				} else {
					//get all of the jobs
					jobs = mpfService.getAllJobRequests();
				}
			}
				
			for (JobRequest job : jobs) {
				long id = job.getId();				
				SingleJobInfo singleJobInfo;
				
				float jobProgressVal = jobProgress.getJobProgress(id) != null ? jobProgress.getJobProgress(id) : 0.0f;
				singleJobInfo = ModelUtils.convertJobRequest(job, jobProgressVal);
				
				jobInfoList.add(singleJobInfo);
			}
		} catch(Exception ex) {
			log.error("exception in get job status with stack trace: {}", ex.getMessage());
		}
		
		return jobInfoList;
	}
		
    private String getSerializedOutputObjectPathVersionOne(long jobId) {
		JobRequest jobRequest = mpfService.getJobRequest(jobId);
		if(jobRequest != null) {
			return jobRequest.getOutputObjectPath();
		} else {
			// TODO: What happens if output object creation hasn't been performed?
			return null;
		}
	}
	
	private <T> T jsonPathToJsonNode(String jsonFilePath, String type, Class<T> returnTypeClass) {
		T jsonOutputObject = null;
		JsonNode jsonNode = null;
		//this just means that the file was read, not that it is valid
		boolean successfulRead = false;
		ObjectMapper mapper = new ObjectMapper();
		
		if(jsonFilePath != null) {
			try {
				FileInputStream inputStream = new FileInputStream(jsonFilePath);
				if(inputStream != null) {
					String allText = IOUtils.toString(inputStream);
					if(allText != null && !allText.isEmpty()) {						
						jsonNode = mapper.readTree(allText);
						successfulRead = true;
					}
					inputStream.close();				
					
					//now try to map it to the object
					if(jsonNode != null) {
						try {
							jsonOutputObject = mapper.treeToValue(jsonNode, returnTypeClass);
						} catch (JsonProcessingException e) {
							log.error("Failed to map json output object file at '{}' to a JsonOutputObject.", jsonFilePath, e);
						}
					} 					
				}
			} catch (Exception ex) {
				log.error("Failed to read the json file at path '{}' in order to produce a json node, raised exception: ", jsonFilePath, ex);
			}
		} else {
			//types can be - output
			log.error("Cannot create a json node without a '{}' output object file path.", type);
		}	
		
		//making sure to log for null inputStream, null or empty read text, and a null jsonNode (though this should throw an
		//	exception from readTree and any issues mapping from a non null jsonNode should throw an exception when mapping to the output object
		//This will like produce multiple log statements when there is an error, but that is better than none and the extra
		//	statement will be helpful!
		if(!successfulRead || jsonNode == null) {
			log.error("Failed to properly read json from the object file at '{}'.", jsonFilePath);
		}	

		return  jsonOutputObject;
	}
	
	private JobCreationResponse resubmitJobVersionOne(long jobIdParam, Integer jobPriorityParam) {
		log.debug("Attempting to resubmit job with id: {}.", jobIdParam);
		//if there is a priority param passed then use it, if not, use the default
		int jobPriority = (jobPriorityParam != null) ? jobPriorityParam : propertiesUtil.getJmsPriority();
		long newJobId = mpfService.resubmitJob(jobIdParam, jobPriority);
		//newJobId should be equal to jobIdParam if there are no issues and -1 if there is a problem
		if(newJobId != -1 && newJobId == jobIdParam) {
			//make sure to reset the value in the job progress map to handle manual refreshes that will display
			//the old progress value (100 in most cases converted to 99 because of the INCOMPLETE STATE)!		
			jobProgress.setJobProgress(newJobId, 0.0f);		
			log.debug("Successful resubmission of Job Id: {} as new JobId: {}", jobIdParam, newJobId);
			return new JobCreationResponse(newJobId);
		}		
		String errorStr = "Failed to resubmit the job with id '" + Long.toString(jobIdParam) + "'. Please check to make sure the job exists before submitting a resubmit request. " 
		+ "Also consider checking the server logs for more information on this error.";
		log.error(errorStr);
		return new JobCreationResponse(1, errorStr);
	}
	
	private MpfResponse cancelJobVersionOne(long jobId)  {
		log.debug("Attempting to cancel job with id: {}.", jobId);
		if(mpfService.cancel(jobId)) {
			log.debug("Successful cancellation of job with id: {}");
			return new MpfResponse(0, null);
		} 
		String errorStr = "Failed to cancel the job with id '" + Long.toString(jobId) + "'. Please check to make sure the job exists before submitting a cancel request. "
				+ "Also consider checking the server logs for more information on this error.";
		log.error(errorStr);
		return new MpfResponse(1, errorStr);
	}
	
	//TODO: bring back once the JobProgress code is completely updated and working
	/*@RequestMapping(value = "/jobs/detailed-progress", method = RequestMethod.GET)
	@ResponseBody
    public JobContainerProgressInfo getDetailedJobProgress(@RequestParam(value = "id", required = false) Long idParam) {
		if(idParam != null) {
			return jobContainerProgress.getJobContainerProgressInfo(idParam);
		}
		log.error("no id parameter set!");
		return new JobContainerProgressInfo();
	}*/
}
