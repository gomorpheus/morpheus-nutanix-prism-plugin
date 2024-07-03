/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.retry.policies

abstract class AbstractRetryDelayPolicy {

  Long maxSleepTime
  static final long DEFAULT_INITIAL_SLEEP_TIME = 1000l //1 second
  Long initialSleepTime = DEFAULT_INITIAL_SLEEP_TIME

  Long getMaxSleepTime() {
    return this.maxSleepTime
  }

  void setMaxSleepTime(Long maxSleepTime) {
    this.maxSleepTime = parseMaxSleepTime(maxSleepTime)
  }

  private static Long parseMaxSleepTime(Long maxSleepTime) {
    if (!maxSleepTime || maxSleepTime <= 0) {
      return null
    }
    return maxSleepTime
  }

  Long getInitialSleepTime() {
    return this.initialSleepTime
  }

  void setInitialSleepTime(Long initialSleepTime) {
    this.initialSleepTime = parseInitialSleepTime(initialSleepTime)
  }

  private static Long parseInitialSleepTime(Long initialSleepTime) {
    if (!initialSleepTime || initialSleepTime <= 0) {
      return null
    }
    return initialSleepTime
  }

  static long getDefaultInitialSleepTime() {
    return DEFAULT_INITIAL_SLEEP_TIME
  }

  abstract Long calculateRetryDelay(Long retryAttempt)

}
