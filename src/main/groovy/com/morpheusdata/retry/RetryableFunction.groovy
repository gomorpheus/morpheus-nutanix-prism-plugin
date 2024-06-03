package com.morpheusdata.retry
/**
 * Provide a retryable function
 * @param <T>
 * @author Chris Taylor
 */
class RetryableFunction<T> {

  // The closure that can be retried
  private Closure retryableClosure
  // The list of arguments to the function
  private ArrayList<T> args

  public RetryableFunction(Closure fn, T... args){
    this.retryableClosure = fn
    this.args = args
  }

  def execute() {
    return this.retryableClosure(*this.args)
  }

  Closure getRetryableClosure() {
    return retryableClosure
  }

  void setRetryableClosure(Closure retryableClosure) {
    this.retryableClosure = retryableClosure
  }

  ArrayList<T> getArgs() {
    return args
  }

  void setArgs(T... args) {
    this.args = args
  }
}
