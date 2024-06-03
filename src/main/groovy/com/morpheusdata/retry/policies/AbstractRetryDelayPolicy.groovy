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
