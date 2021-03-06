#############################################################################
# NOTICE                                                                    #
#                                                                           #
# This software (or technical data) was produced for the U.S. Government    #
# under contract, and is subject to the Rights in Data-General Clause       #
# 52.227-14, Alt. IV (DEC 2007).                                            #
#                                                                           #
# Copyright 2016 The MITRE Corporation. All Rights Reserved.                #
#############################################################################

#############################################################################
# Copyright 2016 The MITRE Corporation                                      #
#                                                                           #
# Licensed under the Apache License, Version 2.0 (the "License");           #
# you may not use this file except in compliance with the License.          #
# You may obtain a copy of the License at                                   #
#                                                                           #
#    http://www.apache.org/licenses/LICENSE-2.0                             #
#                                                                           #
# Unless required by applicable law or agreed to in writing, software       #
# distributed under the License is distributed on an "AS IS" BASIS,         #
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  #
# See the License for the specific language governing permissions and       #
# limitations under the License.                                            #
#############################################################################

project(amq_detection_common)

aux_source_directory(. SRC)

## Find Qt
include(${QT_USE_FILE})
add_definitions(${QT_DEFINITIONS})

## Find ProtoBufs
include_directories(${PROTOBUF_INCLUDE_DIRS})
include_directories(${MPF_PROTO_PATH})

## Find and install activemq-cpp
find_package(PkgConfig)
set(ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:/usr/local/lib/pkgconfig")
pkg_search_module(ACTIVEMQ-CPP REQUIRED activemq-cpp)
include_directories(${ACTIVEMQ-CPP_INCLUDE_DIRS})

file(GLOB MATCHING_FILES
	 ${ACTIVEMQ-CPP_LIBRARY_DIRS}/libactivemq-cpp.so* )
install(FILES ${MATCHING_FILES} DESTINATION lib)

## Find Apache Portable Runtime
set(ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:/usr/local/apr/lib/pkgconfig")
pkg_search_module(APR REQUIRED apr-1)
include_directories(${APR_INCLUDEDIR})

## Install Apache Portable Runtime
file(GLOB MATCHING_FILES ${APR_LIBDIR}/libapr*.so* )
install(FILES ${MATCHING_FILES} DESTINATION lib)

include_directories(${MPF_PROTO_PATH})

add_executable(amq_detection_component ${SRC})
target_link_libraries(amq_detection_component ${MPF_PROTOBUF_LIB} ${PROTOBUF_LIBRARY} ${LOG4CXX_LIBRARIES} ${ACTIVEMQ-CPP_LIBRARIES} ${QT_LIBRARIES})
install(TARGETS amq_detection_component
                RUNTIME DESTINATION bin
                LIBRARY DESTINATION lib
                ARCHIVE DESTINATION lib)

install(FILES Log4cxxConfig.xml DESTINATION config)
