package com.morpheusdata.retry
/**
 * Provide a function that can update params of a RetryableFunction
 * @param <T>
 * @author Chris Taylor
 */
class RetryableFunctionUpdater {
  // The closure that will update params for the RetryableFunction
  private Closure updaterClosure
  // Additional opts to the RetryableFunction
  private opts
  //RetryableFunction to update
  private RetryableFunction retryableFunction

  public RetryableFunctionUpdater(RetryableFunction retryableFunction, Closure updateParamsFunction, opts = null){
    this.updaterClosure = updateParamsFunction
    this.opts = opts
    this.retryableFunction = retryableFunction
  }

  public RetryableFunctionUpdater(Closure updateParamsFunction, opts = null){
    this.updaterClosure = updateParamsFunction
    this.opts = opts
  }

  def updateParams() {
    if (this.retryableFunction) {
      def params = this.retryableFunction.getArgs()
      if (this.opts) {
        params = params + [this.opts]
      }
      if(this.updaterClosure.getParameterTypes()) {
        params = this.updaterClosure(*params)
      } else {
        params = this.updaterClosure()
      }
      this.retryableFunction.setArgs(*params)
    }
  }

  Closure getUpdaterClosure() {
    return updaterClosure
  }

  void setUpdateParamsClosure(Closure updaterClosure) {
    this.updaterClosure = updaterClosure
  }

  Map getOpts() {
    return opts
  }

  void setOpts(opts) {
    this.opts = opts
  }

  RetryableFunction getRetryableFunction() {
    return retryableFunction
  }

  void setRetryableFunction(RetryableFunction retryableFunction) {
    this.retryableFunction = retryableFunction
  }
}
