package com.morpheusdata.retry

interface RetryUtilityInterface {
    
  /**
   * Execute a function and retries per defined policies
   * @param backOffFunction BackOffFunction that will be executed
   * @param updateParamsFunction Function to update params of BackOffFunction
   * @return
   */
  def execute(RetryableFunction backOffFunction, RetryableFunctionUpdater updateParamsFunction)
}
