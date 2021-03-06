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

package org.mitre.mpf.mst;

import com.google.common.base.Stopwatch;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.service.MpfService;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 *
 * NOTE: Please keep the tests in this class in alphabetical order.  While they will automatically run that way regardless
 * of the order in the source code, keeping them in that order helps when correlating jenkins-produced output, which is
 * by job number, with named output, e.g., .../share/output-objects/2/detection.json and face/runFaceCombinedDetectImage.json
 *
 * This class contains tests that were formerly in TestEndToEndJenkins.  See comments in TestSystemOnDiff for information
 * about output checking
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestSystemNightly extends TestSystem {
    private static final Logger log = LoggerFactory.getLogger(TestSystemNightly.class);


    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private MpfService mpfService;

    @Autowired
    private JobProgress jobProgress;

    @Test(timeout = 20*MINUTES)
    public void runDetectMarkupMultipleMediaTypes() throws Exception {
        runSystemTest("DEFAULT_EXTRACTION_FACE_OCV_WITH_MARKUP_PIPELINE",
                "output/face/runDetectMarkupMultipleMediaTypes.json",
                "/samples/motion/five-second-marathon-clip.mkv",
                "/samples/person/video_02.mp4",
                "/samples/face/new_face_video.avi",
                "/samples/face/meds-aa-S001-01.jpg",
                "/samples/face/meds-aa-S029-01.jpg");
    }
    
    @Test(timeout = 5*MINUTES)
    public void runFaceCombinedDetectImage() throws Exception {
        runSystemTest("DEFAULT_EXTRACTION_FACE_COMBINED_PIPELINE", "output/face/runFaceCombinedDetectImage.json",
                "/samples/face/meds-aa-S001-01.jpg",
                "/samples/face/meds-aa-S029-01.jpg",
                "/samples/face/meds-af-S419-01.jpg");
    }

    @Test(timeout = 6*MINUTES)
    public void runMotionPreprocessing() throws Exception {
        runSystemTest("PREPROCESSING_MOTION_EXTRACTION_MOTION_MOG_PIPELINE",
                "output/motion/runMotionPreprocessing.json",
                "/samples/motion/five-second-marathon-clip.mkv",
                "/samples/person/video_02.mp4");
    }

    @Test(timeout = 5*MINUTES)
    public void runMotionPreprocessingFaceDetectionMarkup() throws Exception {
        runSystemTest("DEFAULT_EXTRACTION_FACE_OCV_WITH_MOTION_PREPROCESSOR_AND_MARKUP_PIPELINE",
                "output/motion/runMotionPreprocessingFaceDetectionMarkup.json",
                "/samples/person/video_02.mp4");
    }

    @Test(timeout = 5*MINUTES)
    public void runMotionTracking1() throws Exception {
        testCtr++;
        log.info("Beginning test #{} runMotionTracking1()", testCtr);
        // When tracking is run on these videos it uses the STRUCK algorithm, which is non-deterministic, so there is
        // no output checking
        List<JsonMediaInputObject> media = toMediaObjectList(
                ioUtils.findFile("/samples/motion/five-second-marathon-clip.mkv"),
                ioUtils.findFile("/samples/person/video_02.mp4"));

        long jobId = runPipelineOnMedia("TRACKING_EXTRACTION_MOTION_MOG_PIPELINE", media,
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        log.info("Finished test runMotionTracking1()");
    }

    @Test(timeout = 5*MINUTES)
    public void runMotionTracking2() throws Exception {
        runSystemTest("TRACKING_EXTRACTION_MOTION_MOG_PIPELINE", "output/motion/runMotionTracking.json",
                "/samples/motion/STRUCK_Test_720p.mov");
    }

    @Test(timeout = 4*MINUTES)
    public void testBadPipeline() throws Exception {
        testCtr++;
        log.info("Beginning test #{} testBadPipeline()", testCtr);
        List<JsonMediaInputObject> media = new LinkedList<>();
        media.add(new JsonMediaInputObject("http://somehost-mpf-4.mitre.org/rsrc/datasets/samples/motion/five-second-marathon-clip.mkv"));
        long jobId = runPipelineOnMedia("X", media, propertiesUtil.isOutputObjectsEnabled(),
                propertiesUtil.getJmsPriority());
        log.info("Finished test testBadPipeline()");
    }

    @Test(timeout = 8*MINUTES)
    public void testEmptyMarkupRequest() throws Exception {
        testCtr++;
        log.info("Beginning test #{} testEmptyMarkupRequest()", testCtr);
        List<JsonMediaInputObject> media = toMediaObjectList(
                ioUtils.findFile("/samples/face/meds-aa-S001-01.jpg"),
                ioUtils.findFile("/samples/motion/ocv_motion_video.avi"));
        long jobId = runPipelineOnMedia("DEFAULT_DETECTION_PERSON_WITH_MARKUP_PIPELINE", media,
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        URI outputPath = propertiesUtil.createDetectionOutputObjectFile(jobId).toURI();
//        JsonOutputObject outputObject = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(outputPath)), JsonOutputObject.class);
        JsonOutputObject outputObject = jsonUtils.deserializeFromText(FileUtils.readFileToByteArray(new File(outputPath)), JsonOutputObject.class);
        for(JsonMediaOutputObject mediaOutputObject : outputObject.getMedia()) {
            // Check that the hashes of the source media are equal to the hashes of the markup media result. That is, check that the file was copied successfully.
            Assert.assertEquals(
                    DigestUtils.sha256Hex(FileUtils.readFileToByteArray(new File(URI.create(mediaOutputObject.getPath())))),
                    DigestUtils.sha256Hex(FileUtils.readFileToByteArray(new File(URI.create(mediaOutputObject.getMarkupResult().getPath())))));
        }
        log.info("Finished test testEmptyMarkupRequest()");
    }

    @Test(timeout = 5*MINUTES)
    public void testNonUri() throws Exception {
        testCtr++;
        log.info("Beginning test #{} testNonUri()", testCtr);
        List<JsonMediaInputObject> media = new LinkedList<>();
        media.add(new JsonMediaInputObject("/not/a/file.txt"));
        long jobRequestId = runPipelineOnMedia("DEFAULT_EXTRACTION_PERSON_OCV_PIPELINE", media,
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        log.info("Finished test testNonUri()");
    }

    @Test(timeout = 4*MINUTES)
    public void testTiffImageMarkup() throws Exception {
        testCtr++;
        log.info("Beginning test #{} testTiffImageMarkup()", testCtr);
        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/meds-aa-S001-01.tif"));
        long jobId = runPipelineOnMedia("DEFAULT_EXTRACTION_FACE_OCV_WITH_MARKUP_PIPELINE", media,
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        URI outputPath = propertiesUtil.createDetectionOutputObjectFile(jobId).toURI();
//        JsonOutputObject outputObject = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(outputPath)), JsonOutputObject.class)
        JsonOutputObject outputObject = jsonUtils.deserializeFromText(FileUtils.readFileToByteArray(new File(outputPath)), JsonOutputObject.class);
        for(JsonMediaOutputObject mediaOutputObject : outputObject.getMedia()) {
            // Check that the hashes of the source media are equal to the hashes of the markup media result. That is, check that the file was copied successfully.
            Assert.assertNotEquals(
                    DigestUtils.sha256Hex(FileUtils.readFileToByteArray(new File(URI.create(mediaOutputObject.getPath())))),
                    DigestUtils.sha256Hex(FileUtils.readFileToByteArray(new File(URI.create(mediaOutputObject.getMarkupResult().getPath())))));
            Assert.assertEquals(mediaOutputObject.getMarkupResult().getStatus(), MarkupStatus.COMPLETE.toString());
        }
        log.info("Finished test testTiffImageMarkup()");
    }
    
// This test can also be verified manually (and visually) by running the comparable default pipeline with markup, and a comparable
// custom pipeline with min face size = 100 and markup. The chokepoint video gets 16 faces detected with the default pipeline
// and 14 with the custom pipeline
    @Test(timeout = 20*MINUTES)
    public void runFaceOcvCustomDetectVideo() throws Exception {
        testCtr++;
        log.info("Beginning test #{} runFaceOcvCustomDetectVideo()", testCtr);
        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/person/video_02.mp4"));
        long jobId = runPipelineOnMedia("CUSTOM_OCV_FACE_MIN_FACE_SIZE_100_PIPELINE", media,
//        long jobId = runPipelineOnMedia("DEFAULT_EXTRACTION_FACE_OCV_PIPELINE", mediaPaths,  // to generate default output
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        // Compare the normal Ocv pipeline output with this output.  The custom pipeline output should have fewer track sets
        // on this video (requires a video with some small faces)
        URI defaultOutputPath = (getClass().getClassLoader().getResource("output/face/runFaceOcvCustomDetectVideo-defaultCompare.json")).toURI();
        URI customOutputPath = propertiesUtil.createDetectionOutputObjectFile(jobId).toURI();

        JsonOutputObject defaultOutput = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(defaultOutputPath)), JsonOutputObject.class);
        JsonOutputObject customOutput = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(customOutputPath)), JsonOutputObject.class);

        Set<JsonMediaOutputObject> defMedias = defaultOutput.getMedia();
        Set<JsonMediaOutputObject> custMedias = customOutput.getMedia();

        // the number of media in the custom media group should be the same as the number in the default media group
        Assert.assertEquals(String.format("default MediaGroup size=%s doesn't match custom MediaGroup size=%s",
                defMedias.size(), custMedias.size()), defMedias.size(), custMedias.size());

        Iterator<JsonMediaOutputObject> defIter = defMedias.iterator();
        Iterator<JsonMediaOutputObject> custIter = custMedias.iterator();
        while(defIter.hasNext()){
            compareMedia(defIter.next(), custIter.next());
        }
        log.info("Finished test runFaceOcvCustomDetectVideo()");
    }

    /**
     * For a given media item, compare the number of tracks from the default pipeline and the custom pipeline
     *
     * @param defaultMedia
     * @param customMedia
     */
    private void compareMedia(JsonMediaOutputObject defaultMedia, JsonMediaOutputObject customMedia) {
        Map<String, SortedSet<JsonTrackOutputObject>> defaultTracks = defaultMedia.getTracks();
        Map<String, SortedSet<JsonTrackOutputObject>> customTracks = customMedia.getTracks();
        Iterator<Map.Entry<String, SortedSet<JsonTrackOutputObject>>> defaultEntries = defaultTracks.entrySet().iterator();
        Iterator<Map.Entry<String, SortedSet<JsonTrackOutputObject>>> customEntries = customTracks.entrySet().iterator();

        Assert.assertEquals(String.format("Default track entries size=%d doesn't match custom track entries size=%d",
                defaultTracks.size(), customTracks.size()), defaultTracks.size(), customTracks.size());
        while (customEntries.hasNext()) {
            SortedSet<JsonTrackOutputObject> cusTrackSet = customEntries.next().getValue();
            SortedSet<JsonTrackOutputObject> defTrackSet = defaultEntries.next().getValue();
            int cusTrackSetSize = cusTrackSet.size();
            int defTrackSetSize = defTrackSet.size();
            log.debug("custom number of tracks={}", cusTrackSetSize);
            log.debug("default number of tracks={}", defTrackSetSize);
            Assert.assertTrue(String.format("Custom number of tracks=%d is not less than default number of tracks=%d",
                    cusTrackSetSize, defTrackSetSize), cusTrackSetSize < defTrackSetSize);
        }
    }

    @Test(timeout = 20*MINUTES)
    public void testPriorities() throws Exception {

        // an assumption failure causes the test to be ignored;
        // only run this test on a machine where /mpfdata/datasets is mapped
        Assume.assumeTrue("Skipping test. It should only run on Jenkins.", jenkins);

        log.info("Beginning testPriorities()");

        int TIMEOUT_MILLIS = 15*MINUTES;
        ExecutorService executor = Executors.newFixedThreadPool(4);

        PriorityRunner busyWorkRunner = new PriorityRunner(9);
        PriorityRunner lowRunner = new PriorityRunner(1);
        PriorityRunner highRunner = new PriorityRunner(9);

        // wait until busy work is in progress; fill service message queue(s)
        executor.submit(busyWorkRunner);
        Assert.assertTrue("The busy work job is not in progress. Job may have failed to start.", busyWorkRunner.waitForSomeProgress());

        executor.submit(lowRunner);
        executor.submit(highRunner);

        List<PriorityRunner> priorityRunners = new LinkedList<PriorityRunner>();
        priorityRunners.add(busyWorkRunner);
        priorityRunners.add(lowRunner);
        priorityRunners.add(highRunner);

        PriorityMonitor priorityMonitor = new PriorityMonitor(priorityRunners);
        executor.submit(priorityMonitor);

        executor.shutdown();
        executor.awaitTermination(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        Assert.assertTrue("The busy work job did not complete.", busyWorkRunner.completed);
        Assert.assertTrue("The LOW priority job did not complete.", lowRunner.completed);
        Assert.assertTrue("The HIGH priority job did not complete.", highRunner.completed);

        priorityMonitor.terminate(); // just in case

        Assert.assertFalse("The busy work job failed.", busyWorkRunner.hadError);
        Assert.assertFalse("The LOW priority job failed.", lowRunner.hadError);
        Assert.assertFalse("The HIGH priority job failed.", highRunner.hadError);

        Assert.assertTrue(String.format("The LOW priority job was expected to take longer than the HIGH priority job. (LOW = %d ms, HIGH = %d ms)",
                lowRunner.elapsed, highRunner.elapsed), lowRunner.elapsed > highRunner.elapsed);

        log.info("Finished test #{}. LOW = {} ms, HIGH = {} ms.", testCtr, lowRunner.elapsed, highRunner.elapsed);
    }

    class PriorityRunner implements Runnable {
        public int priority;
        public PriorityRunner(int priority) {
            this.priority = priority;
        }

        public long jobRequestId = -1;
        public long elapsed = 0;
        public boolean completed = false;
        public boolean hadError = false;

        private Stopwatch stopWatch = null;

        @Override
        public void run() {
            tick();
            try {
                // NOTE: Process lots of images so that we get frequent progress updates;
                // one update after each image is processed.
                List<JsonMediaInputObject> media = new LinkedList<>();

                String path = "/mpfdata/datasets/mugshots_2000";
                File dir = new File(path);
                Assert.assertTrue(String.format("%s does not exist.", path), dir.exists());

                for (File file : dir.listFiles()) {
                    media.add(new JsonMediaInputObject(ioUtils.findFile(file.getAbsolutePath()).toString()));
                }

                JsonJobRequest jsonJobRequest = jobRequestBo.createRequest(UUID.randomUUID().toString(),
                        "DEFAULT_EXTRACTION_FACE_OCV_PIPELINE", media, false, priority);
                jobRequestId = mpfService.submitJob(jsonJobRequest);
                completed = waitFor(jobRequestId); // blocking
            } catch (Exception exception) {
                log.error(String.format("Failed to run job %d due to an exception.", jobRequestId), exception);
                hadError = true;
            }
            tock();
        }

        public boolean waitForSomeProgress() {
            int TIMEOUT_MILLIS = 60 * 1000;
            int WAIT_TIME_MILLIS = 5000;

            try {
                for (int t = 0; t < TIMEOUT_MILLIS; t += WAIT_TIME_MILLIS) {
                    log.info("waitForSomeProgress: {}/{} ms, jobRequestId: {}", t, TIMEOUT_MILLIS, jobRequestId); // DEBUG
                    if (jobRequestId != -1) {
                        Float progressObj = jobProgress.getJobProgress(jobRequestId);
                        if (progressObj != null) {
                            if (progressObj > 0) { // cast
                                return true;
                            }
                        }
                    }
                    Thread.sleep(WAIT_TIME_MILLIS);
                }
            } catch (InterruptedException exception) {
                log.error("Failed while waiting for job progress.", exception);
            }

            return false;
        }

        private void tick() {
            stopWatch = Stopwatch.createStarted();
        }

        private void tock() {
            stopWatch.stop();
            elapsed = stopWatch.elapsed(TimeUnit.MILLISECONDS);
        }
    }

    class PriorityMonitor implements Runnable {
        public List<PriorityRunner> priorityRunners = null;
        public PriorityMonitor(List<PriorityRunner> priorityRunners) {
            this.priorityRunners = priorityRunners;
        }

        private boolean terminate = false;
        public void terminate() {
            this.terminate = true;
        }

        @Override
        public void run() {
            int WAIT_TIME_MILLIS = 5000;

            try {
                int time = 0;
                while (!terminate) {
                    int totalProgress = 0;
                    for (PriorityRunner runner : priorityRunners) {
                        float progress = -1f;
                        if (runner.jobRequestId != -1) {
                            Float progressObj = jobProgress.getJobProgress(runner.jobRequestId);
                            if (progressObj != null) {
                                progress = progressObj; // cast
                                totalProgress += progress;
                            }
                        }
                        log.info("time: {} ms, jobRequestId: {}, progress: {}", time, runner.jobRequestId, progress);
                    }
                    if (totalProgress == priorityRunners.size() * 100) {
                        log.info("PriorityMonitor self-terminating. All jobs complete.");
                        return;
                    }
                    Thread.sleep(WAIT_TIME_MILLIS);
                    time += WAIT_TIME_MILLIS;
                }
            } catch (InterruptedException exception) {
                log.error("Failed while monitoring job progress.", exception);
            }
        }
    }
}
