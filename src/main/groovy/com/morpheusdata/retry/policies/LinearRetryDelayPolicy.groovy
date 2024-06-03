package com.morpheusdata.retry.policies

class LinearRetryDelayPolicy extends AbstractRetryDelayPolicy{

  @Override
  Long calculateRetryDelay(Long attempt) {
    Long sleepTime = getDefaultInitialSleepTime()
    if(this.initialSleepTime) {
      sleepTime = this.initialSleepTime
    }
    sleepTime = sleepTime * attempt
    if(this.maxSleepTime && (sleepTime > this.maxSleepTime)) {
      sleepTime = this.maxSleepTime
    }
    return sleepTime
  }

}
