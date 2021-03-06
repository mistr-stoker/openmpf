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

#ifndef TRUNK_DETECTION_GTEST_IMAGE_GENERATION_H_
#define TRUNK_DETECTION_GTEST_IMAGE_GENERATION_H_

#include <vector>
#include <string>

#include "opencv2/core/core.hpp"
#include "opencv2/objdetect/objdetect.hpp"
#include "opencv2/highgui/highgui.hpp"
#include "opencv2/calib3d/calib3d.hpp"
#include "opencv2/imgproc/imgproc.hpp"
#include "opencv2/features2d/features2d.hpp"

#include "detection_base.h"

class ImageGeneration {
  private:
    bool imshow_on;
    bool print_extra_info;

  public:
    ImageGeneration();
    ~ImageGeneration();

    bool Init(bool display_window = false, bool print_debug_info = false);

    std::vector<MPF::COMPONENT::MPFVideoTrack> GetTracks();

    int WriteDetectionOutputImage(const std::string image_in_uri,
                                  const std::vector<MPF::COMPONENT::MPFVideoTrack> &detections,
                                  const std::string image_out_filepath);
    int WriteDetectionOutputImage(const std::string image_in_uri,
                                  const std::vector<MPF::COMPONENT::MPFImageLocation> &detections,
                                  const std::string image_out_filepath);

    cv::Mat RotateFace(const cv::Mat &src);
    cv::Rect GetRandomRect(const cv::Mat &image,
                           const cv::Rect &face_rect,
                           const std::vector<cv::Rect> &existing_rects = std::vector<cv::Rect>());

    int CreateTestImageAndDetectionOutput(const std::vector<cv::Mat> &faces,
                                          bool use_scaling_and_rotation,
                                          const std::string image_out_filepath,
                                          std::vector<MPF::COMPONENT::MPFImageLocation> &detections);
};

#endif  // TRUNK_DETECTION_GTEST_IMAGE_GENERATION_H_
