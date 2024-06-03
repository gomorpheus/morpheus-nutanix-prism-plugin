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
