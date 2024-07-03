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
