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

#ifndef MPF_DETECTION_BUFFER_H_
#define MPF_DETECTION_BUFFER_H_

#include <string>
#include <vector>
#include <map>
#include <log4cxx/logger.h>

#include "detection_base.h"
#include "detection.pb.h"

#include "MPFMessenger.h"

using org::mitre::mpf::wfm::buffers::DetectionError;
using org::mitre::mpf::wfm::buffers::DetectionRequest;
using org::mitre::mpf::wfm::buffers::DetectionRequest_DataType;
using org::mitre::mpf::wfm::buffers::DetectionRequest_AudioRequest;
using org::mitre::mpf::wfm::buffers::DetectionRequest_ImageRequest;
using org::mitre::mpf::wfm::buffers::DetectionRequest_VideoRequest;
using org::mitre::mpf::wfm::buffers::DetectionResponse;
using org::mitre::mpf::wfm::buffers::DetectionResponse_DataType;
using org::mitre::mpf::wfm::buffers::DetectionResponse_AudioResponse;
using org::mitre::mpf::wfm::buffers::DetectionResponse_AudioResponse_AudioTrack;
using org::mitre::mpf::wfm::buffers::DetectionResponse_ImageLocation;
using org::mitre::mpf::wfm::buffers::DetectionResponse_ImageResponse;
using org::mitre::mpf::wfm::buffers::DetectionResponse_VideoResponse;
using org::mitre::mpf::wfm::buffers::DetectionResponse_VideoResponse_VideoTrack;
using org::mitre::mpf::wfm::buffers::DetectionResponse_VideoResponse_VideoTrack_FrameLocationMap;

using std::map;
using std::string;
using std::vector;

using namespace MPF;
using namespace COMPONENT;

struct MPFDetectionVideoRequest {
    int start_frame;
    int stop_frame;
};

struct MPFDetectionAudioRequest {
    int start_time;
    int stop_time;
};

struct MPFDetectionImageRequest {};

class MPFDetectionBuffer {
private:
    const log4cxx::LoggerPtr &logger;
    DetectionRequest detection_request;

    void PackCommonFields(
            const MPFMessageMetadata *const msg_metadata,
            const MPFDetectionDataType data_type,
            const MPFDetectionError error,
            DetectionResponse &detection_response) const;

    unsigned char *FinalizeDetectionResponse(
            const DetectionResponse &detection_response,
            int *packed_length) const;

    static string GetDetectionMetadata(const Properties &detection_properties);


public:
    explicit MPFDetectionBuffer(const log4cxx::LoggerPtr &logger):logger(logger) {};

    ~MPFDetectionBuffer();

    bool UnpackRequest(const unsigned char *const request_contents, const int request_body_length);

    void GetMessageMetadata(MPFMessageMetadata* msg_metadata);

    MPFDetectionDataType GetDataType();

    string GetDataUri();

    void GetAlgorithmProperties(map<string, string> &algorithm_properties);

    void GetMediaProperties(map<string, string> &media_properties);

    void GetVideoRequest(MPFDetectionVideoRequest &video_request);

    void GetAudioRequest(MPFDetectionAudioRequest &audio_request);

    void GetImageRequest(MPFDetectionImageRequest &image_request);

    unsigned char *PackErrorResponse(
            const MPFMessageMetadata *const msg_metadata,
            const MPFDetectionDataType data_type,
            int *packed_length,
            const MPFDetectionError error) const;

    unsigned char *PackVideoResponse(
            const vector<MPFVideoTrack> &tracks,
            const MPFMessageMetadata *const msg_metadata,
            const MPFDetectionDataType data_type,
            const string detection_type,
            int *packed_length,
            const MPFDetectionError error) const;

    unsigned char *PackAudioResponse(
            const vector<MPFAudioTrack> &tracks,
            const MPFMessageMetadata *const msg_metadata,
            const MPFDetectionDataType data_type,
            const string detection_type,
            int *packed_length,
            const MPFDetectionError error) const;

    unsigned char *PackImageResponse(
            const vector<MPFImageLocation> &locations,
            const MPFMessageMetadata *const msg_metadata,
            const MPFDetectionDataType data_type,
            const string detection_type,
            int *packed_length,
            const MPFDetectionError error) const;

    MPFDetectionDataType translateProtobufDataType(const DetectionRequest_DataType &dataType) const;

    DetectionResponse_DataType translateMPFDetectionDataType(const MPFDetectionDataType &dataType) const;

    DetectionError translateMPFDetectionError(const MPFDetectionError &err) const;
};

#endif /* MPF_DETECTION_BUFFER_H_ */
