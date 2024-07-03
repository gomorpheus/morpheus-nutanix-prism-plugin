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

class ExponentialRetryDelayPolicy extends AbstractRetryDelayPolicy {

  Long multiplier

  Long getMultiplier() {
    return multiplier
  }

  void setMultiplier(Long multiplier) {
    this.multiplier = parseMultiplier(multiplier)
  }

  private static Long parseMultiplier(Long multiplier) {
    if (!multiplier || multiplier <= 0) {
      return 2l
    }
    return multiplier
  }

  @Override
  Long calculateRetryDelay(Long attempt) {
    Long sleepTime = getDefaultInitialSleepTime()
    if(this.initialSleepTime) {
      sleepTime = this.initialSleepTime
    }
    if(attempt > 1) {
      sleepTime = (sleepTime * Math.pow(multiplier, attempt)).toLong()
    }
    if(this.maxSleepTime && (sleepTime > this.maxSleepTime)) {
      sleepTime = this.maxSleepTime
    }
    return sleepTime
  }

}
