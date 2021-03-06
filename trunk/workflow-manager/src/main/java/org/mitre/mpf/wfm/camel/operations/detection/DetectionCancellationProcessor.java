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

package org.mitre.mpf.wfm.camel.operations.detection;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf;
import org.springframework.stereotype.Component;

/**
 * This class is used in the Camel routing logic to create a simple response based on the contents of a detection
 * request which was cancelled by the user. The returned response indicates that the request was cancelled.
 */
@Component(DetectionCancellationProcessor.REF)
public class DetectionCancellationProcessor implements Processor {
	public static final String REF = "detectionCancellationProcessor";

	public void process(Exchange exchange) throws Exception {
		// Copy the headers from the incoming message to the outgoing message.
		exchange.getOut().getHeaders().putAll(exchange.getIn().getHeaders());

		DetectionProtobuf.DetectionRequest extractionRequest = DetectionProtobuf.DetectionRequest.parseFrom(exchange.getIn().getBody(byte[].class));

		// Create a simple response based on the request and indicate that the request was cancelled.
		exchange.getOut().setBody(
			DetectionProtobuf.DetectionResponse.newBuilder()
				.setActionIndex(extractionRequest.getActionIndex())
				.setActionName(extractionRequest.getActionName())
				.setDataType(DetectionProtobuf.DetectionResponse.DataType.valueOf(extractionRequest.getDataType().name()))
				.setError(DetectionProtobuf.DetectionError.REQUEST_CANCELLED)
				.setMediaId(extractionRequest.getMediaId())
				.setRequestId(extractionRequest.getRequestId())
				.setStageIndex(extractionRequest.getStageIndex())
				.setStageName(extractionRequest.getStageName())

				// Build the response...
				.build()

				// ...then convert it to a byte array.
				.toByteArray());

	}
}
