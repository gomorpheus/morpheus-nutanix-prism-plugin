package com.morpheusdata.retry.policies

class SimpleRetryDelayPolicy extends AbstractRetryDelayPolicy {

  @Override
  Long calculateRetryDelay(Long attempt) {
    Long sleepTime =  getDefaultInitialSleepTime()
    if(this.initialSleepTime) {
      sleepTime = this.initialSleepTime
    }
    if(this.maxSleepTime && (sleepTime > this.maxSleepTime)) {
      sleepTime = this.maxSleepTime
    }
    return sleepTime
  }
}
