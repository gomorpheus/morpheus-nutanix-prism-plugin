package com.morpheusdata.retry

import com.morpheusdata.retry.policies.AbstractRetryDelayPolicy

import javax.net.ssl.SSLHandshakeException

/**
 * Utility for executing a RetryableFunction
 * @author Chris Taylor
 */
class RetryUtility implements RetryUtilityInterface{

  //Expected communication errors that will trigger a sleep and retry action
  private LinkedHashMap<Class<? extends Exception>, ArrayList<String>> retryableErrors = [(SSLHandshakeException.class): [], (SocketTimeoutException.class): []]
  private AbstractRetryDelayPolicy retryPolicy
  Long maxAttempts = 10
  Long currentAttempt = 0

  RetryUtility(AbstractRetryDelayPolicy retryPolicy, LinkedHashMap<Class<? extends Exception>, ArrayList<String>> retryableErrors = [:], Long maxAttempts = null) {
    if (retryableErrors) {this.retryableErrors = retryableErrors}
    this.retryPolicy = retryPolicy
    if(maxAttempts) {this.maxAttempts = maxAttempts}
  }

  /**
   * Execute a retryable function
   */
  @Override
  def execute(RetryableFunction retryableFunction, RetryableFunctionUpdater retryableFunctionUpdater = null) {
    if (retryableFunctionUpdater) {retryableFunctionUpdater.setRetryableFunction(retryableFunction)}
    for (Long attempt = 1; attempt <= this.maxAttempts; attempt++) {
      this.currentAttempt++
      try {
        return retryableFunction.execute()
      } catch (Exception e) {
        def retryRequired = isRetryRequired(e)
        if(retryRequired) {
          Long sleepTime = retryPolicy.calculateRetryDelay(attempt)
          goToSleep(sleepTime)
        } else {
          throw e
        }
        if (retryableFunctionUpdater) {retryableFunctionUpdater.updateParams()} //update params of RetryFunction
      }
    }
    throw new RetryException( "Maximum Retry Attempts Reached", "1000")
  }
  
  def getRetryableErrors() {
    return retryableErrors
  }

  void setRetryableErrors(LinkedHashMap<Class<? extends Exception>, ArrayList<String>> retryableErrors) {
    this.retryableErrors = retryableErrors
  }

  AbstractRetryDelayPolicy getRetryPolicy() {
    return this.retryPolicy
  }

  void setDelayPolicy(AbstractRetryDelayPolicy retryPolicy) {
    this.retryPolicy = retryPolicy
  }

  Long getMaxAttempts() {
    return this.maxAttempts
  }

  Long getCurrentAttempt() {
    return this.currentAttempt
  }
  
  protected isRetryRequired(Exception e) {
    def rtn = false
    if (e?.getClass() != null) {
      this.retryableErrors.any{ clazz, messages ->
        if (e?.getClass() == clazz) {
            if (messages) {
                rtn = messages.any {it =~ e?.getMessage()}
            } else {
                rtn = true
            }
        }
      }
    }
    return rtn
  }

  /**
   * Thread sleep for sleepTime
   * @param sleepTime
   * @return
   */
  protected static goToSleep(Long sleepTime) {
    Thread.sleep(sleepTime)
  }
}
