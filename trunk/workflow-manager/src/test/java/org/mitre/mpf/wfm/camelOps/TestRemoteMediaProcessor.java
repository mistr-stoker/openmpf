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

package org.mitre.mpf.wfm.camelOps;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultExchange;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mitre.mpf.wfm.camel.WfmProcessorInterface;
import org.mitre.mpf.wfm.camel.WfmSplitterInterface;
import org.mitre.mpf.wfm.camel.operations.mediaretrieval.RemoteMediaProcessor;
import org.mitre.mpf.wfm.camel.operations.mediaretrieval.RemoteMediaSplitter;
import org.mitre.mpf.wfm.data.Redis;
import org.mitre.mpf.wfm.data.entities.transients.TransientJob;
import org.mitre.mpf.wfm.data.entities.transients.TransientMedia;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.PostConstruct;
import java.util.*;

@ContextConfiguration(locations = {"classpath:applicationContext.xml"})
@RunWith(SpringJUnit4ClassRunner.class)
public class TestRemoteMediaProcessor {
	private static final Logger log = LoggerFactory.getLogger(TestRemoteMediaProcessor.class);
	private static final int MINUTES = 1000*60; // 1000 milliseconds/second & 60 seconds/minute.

	@Autowired
	private ApplicationContext context;

	@Autowired
	private CamelContext camelContext;

	@Autowired
	@Qualifier(RemoteMediaProcessor.REF)
	private WfmProcessorInterface remoteMediaProcessor;

	@Autowired
	@Qualifier(RemoteMediaSplitter.REF)
	private WfmSplitterInterface remoteMediaSplitter;

	@Autowired
	private IoUtils ioUtils;

	@Autowired
	private JsonUtils jsonUtils;

	@Autowired
	private Redis redis;

	private TransientJob transientJob;

	private static final MutableInt SEQUENCE = new MutableInt();
	public int next() {
		synchronized (SEQUENCE) {
			int next = SEQUENCE.getValue();
			SEQUENCE.increment();
			return next;
		}
	}

	@PostConstruct
	public void init() throws Exception {
		transientJob = new TransientJob(next(), null, null, 0, 0, false, false) {{
			getMedia().add(new TransientMedia(next(), ioUtils.findFile("/samples/meds1.jpg").toString()));
			getMedia().add(new TransientMedia(next(), "http://info.mitre.org/it_services/images/cit_logo.png"));
		}};
	}

	@Test(timeout = 5 * MINUTES)
	public void testValidRetrieveRequest() throws Exception {
		log.info("Starting valid image retrieval request.");

		TransientMedia transientMedia = new TransientMedia(next(), "http://info.mitre.org/it_services/images/cit_logo.png");
		transientMedia.setLocalPath(ioUtils.createTemporaryFile().getAbsolutePath());

		Exchange exchange = new DefaultExchange(camelContext);
		exchange.getIn().getHeaders().put(MpfHeaders.JOB_ID, next());
		exchange.getIn().setBody(jsonUtils.serialize(transientMedia));
		remoteMediaProcessor.process(exchange);

		Object responseBody = exchange.getOut().getBody();
		Assert.assertTrue("A response body must be set.", responseBody != null);
		Assert.assertTrue(String.format("Response body must be a byte[]. Actual: %s.", responseBody.getClass()),  responseBody instanceof byte[]);

		// Implied assertion: Deserialization works.
		TransientMedia responseTransientMedia = jsonUtils.deserialize((byte[])responseBody, TransientMedia.class);

		Assert.assertTrue(String.format("The response entity must not fail. Actual: %s. Message: %s.",
						Boolean.toString(responseTransientMedia.isFailed()),
						responseTransientMedia.getMessage()),
				!responseTransientMedia.isFailed());

		log.info("Remote valid image retrieval request passed.");
	}

	@Test(timeout = 5 * MINUTES)
	public void testInvalidRetrieveRequest() throws Exception {
		log.info("Starting invalid image retrieval request.");

		TransientMedia transientMedia = new TransientMedia(next(), "https://www.mitre.org/"+UUID.randomUUID().toString());
		transientMedia.setLocalPath(ioUtils.createTemporaryFile().getAbsolutePath());

		Exchange exchange = new DefaultExchange(camelContext);
		exchange.getIn().getHeaders().put(MpfHeaders.JOB_ID, next());
		exchange.getIn().setBody(jsonUtils.serialize(transientMedia));
		remoteMediaProcessor.process(exchange);

		Object responseBody = exchange.getOut().getBody();
		Assert.assertTrue("A response body must be set.", responseBody != null);
		Assert.assertTrue(String.format("Response body must be a byte[]. Actual: %s.", responseBody.getClass()),  responseBody instanceof byte[]);

		// Implied assertion: Deserialization works.
		TransientMedia responseTransientMedia = jsonUtils.deserialize((byte[])responseBody, TransientMedia.class);

		Assert.assertTrue(String.format("The response entity must fail. Actual: %s. Message: %s.",
						Boolean.toString(responseTransientMedia.isFailed()),
						responseTransientMedia.getMessage()),
				responseTransientMedia.isFailed());

		log.info("Remote invalid image retrieval request passed.");
	}

	@Test(timeout = 5 * MINUTES)
	public void testSplitRequest() throws Exception {
		Exchange exchange = new DefaultExchange(camelContext);
		exchange.getIn().setHeader(MpfHeaders.JOB_ID, next());
		exchange.getIn().setBody(jsonUtils.serialize(transientJob));

		List<Message> messages = remoteMediaSplitter.split(exchange);

		int targetMessageCount = 1;
		Assert.assertTrue(String.format("The splitter must return %d message. Actual: %d.", targetMessageCount, messages.size()),
				targetMessageCount == messages.size());

		Object messageBody = messages.get(0).getBody();
		Assert.assertTrue("The splitter must assign a body value to the message it created.", messageBody != null);
		Assert.assertTrue("The request body for the message must be a byte[].", messageBody instanceof byte[]);

		TransientMedia transientMedia = jsonUtils.deserialize((byte[])(messageBody), TransientMedia.class);
		Assert.assertTrue("The local path must not begin with 'file:'.", !transientMedia.getLocalPath().startsWith("file:"));
		Assert.assertTrue("The transient file must not be marked as failed.", !transientMedia.isFailed());
	}
}
