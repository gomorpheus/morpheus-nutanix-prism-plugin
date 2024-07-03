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
